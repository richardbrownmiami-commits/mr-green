#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <malloc.h>

#if defined(__ANDROID__)
#include <android/log.h>
#define MRLOG(level, fmt, ...) __android_log_print(level, "MrGreenLLM", fmt, ##__VA_ARGS__)
#else
#define MRLOG(level, fmt, ...) std::fprintf(stderr, fmt "\n", ##__VA_ARGS__)
#endif

namespace {
std::mutex g_mutex;
std::atomic<bool> g_abort{false};
std::atomic<int64_t> g_deadline_ms{0};
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
std::string g_path;
std::string g_architecture;
std::string g_model_name;
std::string g_template;
std::string g_template_kind;
std::string g_tokenizer_type;
int g_nctx = 0;
int g_vocab = 0;
double g_prompt_tps = 0, g_gen_tps = 0;
int64_t g_kv_bytes = -1;
std::once_flag g_backend_once;
std::vector<llama_token> g_cached_prompt;

int64_t monotonic_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
bool timed_out() { return g_deadline_ms.load() > 0 && monotonic_ms() >= g_deadline_ms.load(); }
bool should_abort() { return g_abort.load() || timed_out(); }
struct GenerationDeadline {
    GenerationDeadline() { g_deadline_ms = monotonic_ms() + 180000; }
    ~GenerationDeadline() { g_deadline_ms = 0; }
};
const llama_vocab *vocab() { return g_model ? llama_model_get_vocab(g_model) : nullptr; }

std::string jstr(JNIEnv *env, jstring value) {
    if (!value) return {};
    const jsize size = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::runtime_error("Unable to read Java string");
    std::string result;
    result.reserve(static_cast<size_t>(size) * 3);
    for (jsize i = 0; i < size; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < size && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
            cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
        else if (cp >= 0xd800 && cp <= 0xdfff) cp = 0xfffd;
        if (cp < 0x80) result.push_back(static_cast<char>(cp));
        else if (cp < 0x800) { result.push_back(static_cast<char>(0xc0 | (cp >> 6))); result.push_back(static_cast<char>(0x80 | (cp & 63))); }
        else if (cp < 0x10000) { result.push_back(static_cast<char>(0xe0 | (cp >> 12))); result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63))); result.push_back(static_cast<char>(0x80 | (cp & 63))); }
        else { result.push_back(static_cast<char>(0xf0 | (cp >> 18))); result.push_back(static_cast<char>(0x80 | ((cp >> 12) & 63))); result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63))); result.push_back(static_cast<char>(0x80 | (cp & 63))); }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring jstring_utf8(JNIEnv *env, const std::string &bytes) {
    std::vector<jchar> chars;
    chars.reserve(bytes.size());
    for (size_t i = 0; i < bytes.size();) {
        const uint8_t lead = static_cast<uint8_t>(bytes[i++]);
        uint32_t cp = 0; int continuation = 0;
        if (lead < 0x80) cp = lead;
        else if (lead >= 0xc2 && lead <= 0xdf) { cp = lead & 31; continuation = 1; }
        else if (lead >= 0xe0 && lead <= 0xef) { cp = lead & 15; continuation = 2; }
        else if (lead >= 0xf0 && lead <= 0xf4) { cp = lead & 7; continuation = 3; }
        else cp = 0xfffd;
        bool valid = i + continuation <= bytes.size();
        for (int j = 0; j < continuation && valid; ++j) {
            const uint8_t c = static_cast<uint8_t>(bytes[i + j]);
            if ((c & 0xc0) != 0x80) valid = false; else cp = (cp << 6) | (c & 63);
        }
        if (!valid) cp = 0xfffd; else i += continuation;
        if ((continuation == 2 && cp < 0x800) || (continuation == 3 && cp < 0x10000) || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) cp = 0xfffd;
        if (cp <= 0xffff) chars.push_back(static_cast<jchar>(cp));
        else { cp -= 0x10000; chars.push_back(static_cast<jchar>(0xd800 + (cp >> 10))); chars.push_back(static_cast<jchar>(0xdc00 + (cp & 1023))); }
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

jstring error(JNIEnv *env, const std::string &message) { return jstring_utf8(env, message); }

std::string meta(const char *key) {
    if (!g_model) return {};
    char value[1024] = {};
    return llama_model_meta_val_str(g_model, key, value, sizeof(value)) >= 0 ? std::string(value) : std::string();
}

std::string classify_template(const std::string &source) {
    if (source.empty()) return "missing";
    const std::string s = source;
    if (s.find("qwen") != std::string::npos || s.find("Qwen") != std::string::npos || s.find("<|im_start|>") != std::string::npos) return "qwen/chatml-family";
    if (s.find("<|start_header_id|>") != std::string::npos) return "llama3";
    if (s.find("<start_of_turn>") != std::string::npos) return "gemma";
    if (s.find("[INST]") != std::string::npos) return "llama2/mistral";
    if (s.find("<|assistant|>") != std::string::npos) return "phi/role-token";
    if (s.find("<|START_OF_TURN_TOKEN|>") != std::string::npos) return "command-r";
    if (s.find("<think>") != std::string::npos) return "reasoning";
    return "embedded/custom";
}

std::vector<llama_token> tokenize(const std::string &text, bool special) {
    if (!vocab()) return {};
    // The chat template already owns BOS/EOS. Adding BOS here was a major source of
    // malformed prompts for models whose template emits its own BOS token.
    int count = llama_tokenize(vocab(), text.data(), static_cast<int32_t>(text.size()), nullptr, 0, false, special);
    if (count >= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(-count));
    count = llama_tokenize(vocab(), text.data(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), false, special);
    if (count < 0) return {};
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

bool progress(JNIEnv *env, jobject callback, const char *stage, int completed, int total) {
    if (!callback) return true;
    jclass cls = env->GetObjectClass(callback);
    if (!cls) return false;
    jmethodID method = env->GetMethodID(cls, "onProgress", "(Ljava/lang/String;II)V");
    jstring label = method ? env->NewStringUTF(stage) : nullptr;
    if (method && label) env->CallVoidMethod(callback, method, label, completed, total);
    if (label) env->DeleteLocalRef(label);
    env->DeleteLocalRef(cls);
    return !env->ExceptionCheck();
}

class Utf8Emitter {
public:
    Utf8Emitter(JNIEnv *env, jobject callback) : env_(env), callback_(callback) {}
    bool push(const std::string &bytes, bool final = false) {
        pending_.append(bytes);
        std::string ready;
        size_t i = 0;
        while (i < pending_.size()) {
            const uint8_t c = static_cast<uint8_t>(pending_[i]);
            int length = c < 0x80 ? 1 : (c >= 0xc2 && c <= 0xdf ? 2 : (c >= 0xe0 && c <= 0xef ? 3 : (c >= 0xf0 && c <= 0xf4 ? 4 : 0)));
            if (!length) { ready += "\xef\xbf\xbd"; ++i; continue; }
            if (i + length > pending_.size()) { if (!final) break; ready += "\xef\xbf\xbd"; ++i; continue; }
            bool valid = true;
            for (int j = 1; j < length; ++j) if ((static_cast<uint8_t>(pending_[i + j]) & 0xc0) != 0x80) valid = false;
            if (!valid) { ready += "\xef\xbf\xbd"; ++i; } else { ready.append(pending_, i, static_cast<size_t>(length)); i += length; }
        }
        pending_.erase(0, i);
        if (final && !pending_.empty()) { ready += "\xef\xbf\xbd"; pending_.clear(); }
        if (!callback_ || ready.empty()) return true;
        jclass cls = env_->GetObjectClass(callback_);
        if (!cls) return false;
        jmethodID method = env_->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        jstring value = method ? jstring_utf8(env_, ready) : nullptr;
        if (method && value) env_->CallVoidMethod(callback_, method, value);
        if (value) env_->DeleteLocalRef(value);
        env_->DeleteLocalRef(cls);
        return !env_->ExceptionCheck();
    }
private:
    JNIEnv *env_; jobject callback_; std::string pending_;
};

std::string make_prompt(JNIEnv *env, jobjectArray messages) {
    const jsize size = env->GetArrayLength(messages);
    if (size % 2) throw std::runtime_error("Messages must be alternating role/content strings");
    std::vector<std::string> roles, contents;
    for (jsize i = 0; i < size; i += 2) {
        jstring role = static_cast<jstring>(env->GetObjectArrayElement(messages, i));
        jstring content = static_cast<jstring>(env->GetObjectArrayElement(messages, i + 1));
        roles.push_back(jstr(env, role)); contents.push_back(jstr(env, content));
        env->DeleteLocalRef(role); env->DeleteLocalRef(content);
    }
    // Qwen3's soft switch is part of its trained format and is safer than injecting a
    // bare </think> token. This keeps reasoning off while preserving the template.
    if (g_architecture.rfind("qwen3", 0) == 0 && !contents.empty() && roles.back() == "user") contents.back() += "\n/no_think";

    const char *templ = llama_model_chat_template(g_model, nullptr);
    if (!templ) throw std::runtime_error("MODEL_TEMPLATE_MISSING: GGUF has no embedded chat template");
    std::vector<llama_chat_message> chat;
    for (size_t i = 0; i < roles.size(); ++i) chat.push_back({roles[i].c_str(), contents[i].c_str()});
    int needed = llama_chat_apply_template(templ, chat.data(), chat.size(), true, nullptr, 0);
    if (needed <= 0) throw std::runtime_error("MODEL_TEMPLATE_UNSUPPORTED: embedded chat template could not be rendered");
    std::vector<char> output(static_cast<size_t>(needed) + 1);
    const int written = llama_chat_apply_template(templ, chat.data(), chat.size(), true, output.data(), output.size());
    if (written < 0) throw std::runtime_error("MODEL_TEMPLATE_RENDER_FAILED: embedded chat template failed");
    return std::string(output.data(), static_cast<size_t>(written));
}

int decode_prompt(const std::vector<llama_token> &tokens, int batch, JNIEnv *env, jobject callback) {
    const int capacity = std::min(batch, static_cast<int>(llama_n_ubatch(g_ctx)));
    if (capacity < 1) return -1;
    if (!progress(env, callback, "prompt", 0, static_cast<int>(tokens.size()))) return -3;
    for (size_t offset = 0; offset < tokens.size();) {
        if (should_abort()) return 2;
        const int count = static_cast<int>(std::min<size_t>(capacity, tokens.size() - offset));
        llama_batch b = llama_batch_init(count, 0, 1);
        b.n_tokens = count;
        if (!b.token || !b.pos || !b.n_seq_id || !b.seq_id || !b.logits) { llama_batch_free(b); return -2; }
        for (int i = 0; i < count; ++i) {
            b.token[i] = tokens[offset + i];
            b.pos[i] = static_cast<llama_pos>(offset + i);
            b.n_seq_id[i] = 1; b.seq_id[i][0] = 0; b.logits[i] = i == count - 1;
        }
        const int rc = llama_decode(g_ctx, b);
        llama_batch_free(b);
        if (rc) return rc;
        offset += count;
        if (!progress(env, callback, "prompt", static_cast<int>(offset), static_cast<int>(tokens.size()))) return -3;
    }
    return 0;
}

void clear_model() {
    g_cached_prompt.clear();
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_ctx = nullptr; g_model = nullptr; g_path.clear(); g_architecture.clear(); g_model_name.clear(); g_template.clear(); g_template_kind.clear(); g_tokenizer_type.clear(); g_nctx = 0; g_vocab = 0; g_prompt_tps = g_gen_tps = 0; g_kv_bytes = -1;
}

template<class F> jstring guarded(JNIEnv *env, F fn) {
    try { return fn(); }
    catch (const std::exception &e) { MRLOG(ANDROID_LOG_ERROR, "native exception: %s", e.what()); return error(env, e.what()); }
    catch (...) { MRLOG(ANDROID_LOG_ERROR, "unknown native exception"); return error(env, "Unknown native engine error"); }
}

bool parse_int(const std::string &text, int &result) {
    if (text.empty()) return false;
    char *end = nullptr; long value = std::strtol(text.c_str(), &end, 10);
    if (*end || value < 1 || value > 1000000) return false;
    result = static_cast<int>(value); return true;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring path, jint nctx, jint batch, jint ubatch, jint threads) {
    return guarded(env, [&]() -> jstring {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_abort = false; clear_model();
        if (nctx < 512 || nctx > 2048 || threads < 1 || threads > 8 || batch < 1 || batch > nctx || ubatch < 1 || ubatch > batch)
            return error(env, "Invalid native load parameters");
        std::call_once(g_backend_once, [] { llama_backend_init(); });
        g_path = jstr(env, path);
        MRLOG(ANDROID_LOG_INFO, "LOAD begin path=%s ctx=%d batch=%d ubatch=%d threads=%d", g_path.c_str(), nctx, batch, ubatch, threads);
        auto mp = llama_model_default_params();
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.lazy_mode = LLAMA_LAZY_MODE_OFF;
        mp.n_gpu_layers = 0;
        g_model = llama_model_load_from_file(g_path.c_str(), mp);
        if (!g_model) { MRLOG(ANDROID_LOG_ERROR, "LOAD failed: llama_model_load_from_file returned null"); clear_model(); return error(env, "MODEL_LOAD_FAILED: llama.cpp could not load the GGUF; check ABI, file integrity, quantization and available address space"); }
        g_architecture = meta("general.architecture");
        g_model_name = meta("general.name");
        g_template = llama_model_chat_template(g_model, nullptr) ? llama_model_chat_template(g_model, nullptr) : "";
        g_template_kind = classify_template(g_template);
        g_tokenizer_type = meta("tokenizer.ggml.model");
        g_vocab = llama_vocab_n_tokens(vocab());
        MRLOG(ANDROID_LOG_INFO, "MODEL detected arch=%s name=%s template=%s tokenizer=%s vocab=%d params=%llu bytes=%llu", g_architecture.c_str(), g_model_name.c_str(), g_template_kind.c_str(), g_tokenizer_type.c_str(), g_vocab, static_cast<unsigned long long>(llama_model_n_params(g_model)), static_cast<unsigned long long>(llama_model_size(g_model)));
        if (g_template.empty()) { clear_model(); return error(env, "MODEL_TEMPLATE_MISSING: this GGUF does not contain a usable chat template"); }
        auto cp = llama_context_default_params();
        cp.n_ctx = nctx; cp.n_batch = batch; cp.n_ubatch = ubatch; cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED; cp.offload_kqv = false; cp.op_offload = false;
        cp.abort_callback = [](void *) { return should_abort(); };
        g_ctx = llama_init_from_model(g_model, cp);
        if (!g_ctx) { MRLOG(ANDROID_LOG_ERROR, "LOAD context failed arch=%s model=%s", g_architecture.c_str(), g_model_name.c_str()); clear_model(); return error(env, "CONTEXT_CREATE_FAILED: model loaded but inference context could not be created; reduce context/batch or use a smaller GGUF"); }
        g_nctx = nctx;
        MRLOG(ANDROID_LOG_INFO, "LOAD success arch=%s template=%s tokenizer=%s ctx=%d", g_architecture.c_str(), g_template_kind.c_str(), g_tokenizer_type.c_str(), g_nctx);
        return nullptr;
    });
}

extern "C" JNIEXPORT void JNICALL Java_im_mrgreen_engine_LlamaEngine_nativeUnload(JNIEnv *, jobject) { g_abort = true; std::lock_guard<std::mutex> lock(g_mutex); clear_model(); }
extern "C" JNIEXPORT void JNICALL Java_im_mrgreen_engine_LlamaEngine_nativeCancel(JNIEnv *, jobject) { g_abort = true; }

extern "C" JNIEXPORT jint JNICALL Java_im_mrgreen_engine_LlamaEngine_nativeCountTokens(JNIEnv *env, jobject, jstring text) {
    try { std::lock_guard<std::mutex> lock(g_mutex); if (!g_model) return -1; return static_cast<jint>(tokenize(jstr(env, text), false).size()); } catch (...) { return -1; }
}

extern "C" JNIEXPORT jstring JNICALL Java_im_mrgreen_engine_LlamaEngine_nativeStats(JNIEnv *env, jobject) {
    return guarded(env, [&]() -> jstring {
        std::lock_guard<std::mutex> lock(g_mutex); int64_t heap = -1;
#if defined(__ANDROID__)
        struct mallinfo mi = mallinfo(); heap = mi.uordblks;
#endif
        char output[4096];
        std::snprintf(output, sizeof(output), "%s\t%d\t%.5f\t%.5f\t%lld\t%lld\t%s\t%s\t%s\t%s\t%d", g_path.c_str(), g_nctx, g_prompt_tps, g_gen_tps, static_cast<long long>(g_kv_bytes), static_cast<long long>(heap), g_architecture.c_str(), g_model_name.c_str(), g_template_kind.c_str(), g_tokenizer_type.c_str(), g_vocab);
        return jstring_utf8(env, output);
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeGenerate(JNIEnv *env, jobject, jobjectArray messages, jobjectArray options, jobject callback) {
    return guarded(env, [&]() -> jstring {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_ctx || !g_model) return error(env, "MODEL_NOT_LOADED: no local model is loaded");
        g_abort = false; GenerationDeadline deadline;
        const jsize option_count = env->GetArrayLength(options); std::vector<std::string> opts;
        for (jsize i = 0; i < option_count; ++i) { jstring v = static_cast<jstring>(env->GetObjectArrayElement(options, i)); opts.push_back(jstr(env, v)); env->DeleteLocalRef(v); }
        int requested = 512; if (!opts.empty() && !parse_int(opts[0], requested)) requested = 512;
        // 128 tokens is too small for reasoning-capable Qwen models and can end inside the
        // reasoning section. Keep the app's old value as a lower bound of 512 here.
        int max_tokens = std::clamp(std::max(requested, 512), 1, 2048);
        float temperature = 0.7f, top_p = 0.8f, min_p = 0.0f; int top_k = 20;
        if (opts.size() > 1) temperature = std::strtof(opts[1].c_str(), nullptr);
        if (opts.size() > 2) top_k = std::atoi(opts[2].c_str());
        if (opts.size() > 3) top_p = std::strtof(opts[3].c_str(), nullptr);
        if (opts.size() > 4) min_p = std::strtof(opts[4].c_str(), nullptr);
        if (!std::isfinite(temperature)) temperature = 0.7f; if (!std::isfinite(top_p)) top_p = 0.8f; if (!std::isfinite(min_p)) min_p = 0.0f; top_k = std::max(1, top_k);
        const std::string prompt = make_prompt(env, messages);
        const std::vector<llama_token> tokens = tokenize(prompt, true);
        MRLOG(ANDROID_LOG_INFO, "GEN begin arch=%s template=%s tokens=%zu max=%d temp=%.3f topk=%d topp=%.3f", g_architecture.c_str(), g_template_kind.c_str(), tokens.size(), max_tokens, temperature, top_k, top_p);
        if (tokens.empty()) return error(env, "TOKENIZE_FAILED: chat prompt produced zero tokens");
        if (tokens.size() + 1 > static_cast<size_t>(g_nctx)) return error(env, "CONTEXT_TOO_SMALL: prompt exceeds context length");
        max_tokens = std::min(max_tokens, g_nctx - static_cast<int>(tokens.size()));
        if (max_tokens < 1) return error(env, "CONTEXT_TOO_SMALL: no room remains for generation");

        llama_memory_t memory = llama_get_memory(g_ctx); llama_memory_clear(memory, true); g_cached_prompt.clear();
        auto prompt_start = std::chrono::steady_clock::now();
        int rc = decode_prompt(tokens, static_cast<int>(llama_n_batch(g_ctx)), env, callback);
        if (rc || should_abort()) {
            llama_memory_clear(memory, true); g_cached_prompt.clear();
            MRLOG(ANDROID_LOG_ERROR, "GEN prompt decode failed rc=%d abort=%d timeout=%d", rc, g_abort.load(), timed_out());
            if (timed_out()) return error(env, "GEN_TIMEOUT: prompt evaluation exceeded 180 seconds");
            return error(env, g_abort ? "Generation cancelled" : "PROMPT_DECODE_FAILED: llama_decode failed before first token");
        }
        g_cached_prompt = tokens; double prompt_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - prompt_start).count(); g_prompt_tps = prompt_seconds > 0 ? tokens.size() / prompt_seconds : 0;

        using SamplerPtr = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        SamplerPtr sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), &llama_sampler_free);
        if (!sampler) return error(env, "SAMPLER_INIT_FAILED");
        if (opts.size() > 6 && !opts[6].empty()) {
            llama_sampler *grammar = llama_sampler_init_grammar(vocab(), opts[6].c_str(), "root");
            if (!grammar) return error(env, "GRAMMAR_INIT_FAILED: invalid GBNF grammar");
            llama_sampler_chain_add(sampler.get(), grammar);
        }
        // Do not apply the old 1.5 presence penalty. It is not a Qwen default and can badly
        // distort short mobile generations. Keep repetition/presence neutral for the baseline.
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab()), 64, 1.0f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(static_cast<uint32_t>(std::chrono::steady_clock::now().time_since_epoch().count())));
        Utf8Emitter emitter(env, callback); auto generation_start = std::chrono::steady_clock::now(); int generated = 0;
        if (!progress(env, callback, "generate", 0, max_tokens)) return nullptr;
        for (; generated < max_tokens && !should_abort(); ++generated) {
            llama_token token = llama_sampler_sample(sampler.get(), g_ctx, -1);
            if (llama_vocab_is_eog(vocab(), token)) break;
            llama_sampler_accept(sampler.get(), token);
            llama_batch one = llama_batch_init(1, 0, 1); one.n_tokens = 1;
            if (!one.token || !one.pos || !one.n_seq_id || !one.seq_id || !one.logits) { llama_batch_free(one); rc = -2; break; }
            one.token[0] = token; one.pos[0] = static_cast<llama_pos>(tokens.size() + generated); one.n_seq_id[0] = 1; one.seq_id[0][0] = 0; one.logits[0] = true;
            rc = llama_decode(g_ctx, one); llama_batch_free(one);
            if (rc) break;
            char piece[1024]; int piece_size = llama_token_to_piece(vocab(), token, piece, sizeof(piece), 0, false);
            if (piece_size > 0 && !emitter.push(std::string(piece, static_cast<size_t>(piece_size)))) { rc = -3; break; }
            if (!progress(env, callback, "generate", generated + 1, max_tokens)) { rc = -3; break; }
        }
        g_gen_tps = (std::chrono::duration<double>(std::chrono::steady_clock::now() - generation_start).count() > 0) ? generated / std::chrono::duration<double>(std::chrono::steady_clock::now() - generation_start).count() : 0;
        MRLOG(ANDROID_LOG_INFO, "GEN end generated=%d rc=%d abort=%d p_tps=%.3f g_tps=%.3f", generated, rc, g_abort.load(), g_prompt_tps, g_gen_tps);
        if (rc || should_abort()) {
            llama_memory_clear(memory, true); g_cached_prompt.clear();
            if (timed_out()) return error(env, "GEN_TIMEOUT: token generation exceeded 180 seconds");
            return error(env, g_abort ? "Generation cancelled" : "TOKEN_DECODE_FAILED: llama_decode failed during generation");
        }
        return nullptr;
    });
}
