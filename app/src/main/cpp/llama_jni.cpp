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

namespace {
std::mutex g_mutex;
std::atomic<bool> g_abort{false};
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
std::string g_path;
int g_nctx = 0;
double g_prompt_tps = 0, g_gen_tps = 0;
int64_t g_kv_bytes = -1;
std::once_flag g_backend_once;
std::vector<llama_token> g_cached_prompt;

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
        else if (cp < 0x800) {
            result.push_back(static_cast<char>(0xc0 | (cp >> 6)));
            result.push_back(static_cast<char>(0x80 | (cp & 63)));
        } else if (cp < 0x10000) {
            result.push_back(static_cast<char>(0xe0 | (cp >> 12)));
            result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
            result.push_back(static_cast<char>(0x80 | (cp & 63)));
        } else {
            result.push_back(static_cast<char>(0xf0 | (cp >> 18)));
            result.push_back(static_cast<char>(0x80 | ((cp >> 12) & 63)));
            result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
            result.push_back(static_cast<char>(0x80 | (cp & 63)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring jstring_utf8(JNIEnv *env, const std::string &bytes) {
    std::vector<jchar> chars;
    chars.reserve(bytes.size());
    for (size_t i = 0; i < bytes.size();) {
        const uint8_t lead = static_cast<uint8_t>(bytes[i++]);
        uint32_t cp = 0;
        int continuation = 0;
        if (lead < 0x80) cp = lead;
        else if (lead >= 0xc2 && lead <= 0xdf) { cp = lead & 31; continuation = 1; }
        else if (lead >= 0xe0 && lead <= 0xef) { cp = lead & 15; continuation = 2; }
        else if (lead >= 0xf0 && lead <= 0xf4) { cp = lead & 7; continuation = 3; }
        else cp = 0xfffd;
        bool valid = i + continuation <= bytes.size();
        for (int j = 0; j < continuation && valid; ++j) {
            const uint8_t c = static_cast<uint8_t>(bytes[i + j]);
            if ((c & 0xc0) != 0x80) valid = false;
            else cp = (cp << 6) | (c & 63);
        }
        if (!valid) cp = 0xfffd;
        else i += continuation;
        if ((continuation == 2 && cp < 0x800) || (continuation == 3 && cp < 0x10000) ||
            cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) cp = 0xfffd;
        if (cp <= 0xffff) chars.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            chars.push_back(static_cast<jchar>(0xd800 + (cp >> 10)));
            chars.push_back(static_cast<jchar>(0xdc00 + (cp & 1023)));
        }
    }
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

jstring error(JNIEnv *env, const char *message) { return jstring_utf8(env, message); }

std::vector<llama_token> tokenize(const std::string &text, bool special) {
    int count = llama_tokenize(vocab(), text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, special);
    if (count >= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(-count));
    count = llama_tokenize(vocab(), text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), true, special);
    if (count < 0) return {};
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

int decode_tokens(const std::vector<llama_token> &tokens, int batch, size_t position) {
    for (size_t offset = 0; offset < tokens.size();) {
        const int count = static_cast<int>(std::min<size_t>(batch, tokens.size() - offset));
        llama_batch batch_data = llama_batch_init(count, 0, 1);
        for (int i = 0; i < count; ++i) {
            batch_data.token[i] = tokens[offset + i];
            batch_data.pos[i] = static_cast<llama_pos>(position + offset + i);
            batch_data.n_seq_id[i] = 1;
            batch_data.seq_id[i][0] = 0;
            batch_data.logits[i] = i == count - 1;
        }
        const int rc = llama_decode(g_ctx, batch_data);
        llama_batch_free(batch_data);
        if (rc) return rc;
        offset += count;
    }
    return 0;
}

// Preserve an incomplete trailing code point across token callbacks. Invalid bytes are
// replaced, while a valid but incomplete suffix remains pending until the next token.
class Utf8Emitter {
public:
    Utf8Emitter(JNIEnv *env, jobject callback) : env_(env), callback_(callback) {}
    bool push(const std::string &bytes, bool final = false) {
        pending_.append(bytes);
        std::string ready;
        size_t i = 0;
        while (i < pending_.size()) {
            const uint8_t c = static_cast<uint8_t>(pending_[i]);
            int length = c < 0x80 ? 1 : (c >= 0xc2 && c <= 0xdf ? 2 :
                         (c >= 0xe0 && c <= 0xef ? 3 : (c >= 0xf0 && c <= 0xf4 ? 4 : 0)));
            if (!length) { ready += "\xef\xbf\xbd"; ++i; continue; }
            if (i + length > pending_.size()) {
                bool prefix_ok = true;
                for (size_t j = i + 1; j < pending_.size(); ++j)
                    if ((static_cast<uint8_t>(pending_[j]) & 0xc0) != 0x80) prefix_ok = false;
                if (prefix_ok && !final) break;
                ready += "\xef\xbf\xbd";
                ++i;
                continue;
            }
            bool valid = true;
            for (int j = 1; j < length; ++j)
                if ((static_cast<uint8_t>(pending_[i + j]) & 0xc0) != 0x80) valid = false;
            if (valid && length > 1) {
                const uint8_t second = static_cast<uint8_t>(pending_[i + 1]);
                if ((c == 0xe0 && second < 0xa0) || (c == 0xed && second >= 0xa0) ||
                    (c == 0xf0 && second < 0x90) || (c == 0xf4 && second >= 0x90)) valid = false;
            }
            if (!valid) { ready += "\xef\xbf\xbd"; ++i; }
            else { ready.append(pending_, i, static_cast<size_t>(length)); i += length; }
        }
        pending_.erase(0, i);
        if (final && !pending_.empty()) { ready += "\xef\xbf\xbd"; pending_.clear(); }
        return ready.empty() || emit_complete(ready);
    }
private:
    bool emit_complete(const std::string &text) {
        if (!callback_ || text.empty()) return true;
        jclass cls = env_->GetObjectClass(callback_);
        if (!cls) return !env_->ExceptionCheck();
        jmethodID method = env_->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        jstring value = method ? jstring_utf8(env_, text) : nullptr;
        if (method && value) env_->CallVoidMethod(callback_, method, value);
        if (value) env_->DeleteLocalRef(value);
        env_->DeleteLocalRef(cls);
        return !env_->ExceptionCheck();
    }
    JNIEnv *env_;
    jobject callback_;
    std::string pending_;
};

std::string make_prompt(JNIEnv *env, jobjectArray messages) {
    const jsize size = env->GetArrayLength(messages);
    if (size % 2) throw std::runtime_error("Messages must be alternating role/content strings");
    std::vector<std::string> roles, contents;
    for (jsize i = 0; i < size; i += 2) {
        jstring role = static_cast<jstring>(env->GetObjectArrayElement(messages, i));
        jstring content = static_cast<jstring>(env->GetObjectArrayElement(messages, i + 1));
        roles.push_back(jstr(env, role));
        contents.push_back(jstr(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    const char *templ = llama_model_chat_template(g_model, nullptr);
    if (!templ) throw std::runtime_error("Model has no embedded chat template; refusing to fabricate a prompt");
    std::vector<llama_chat_message> chat;
    for (size_t i = 0; i < roles.size(); ++i) chat.push_back({roles[i].c_str(), contents[i].c_str()});
    int needed = llama_chat_apply_template(templ, chat.data(), chat.size(), true, nullptr, 0);
    if (needed <= 0) throw std::runtime_error("Embedded chat template is unsupported by this llama.cpp version");
    std::vector<char> output(static_cast<size_t>(needed) + 1);
    const int written = llama_chat_apply_template(templ, chat.data(), chat.size(), true, output.data(), output.size());
    if (written < 0) throw std::runtime_error("Could not apply embedded chat template");
    std::string prompt(output.data(), static_cast<size_t>(written));

    char architecture[128] = {};
    const bool qwen3 = llama_model_meta_val_str(g_model, "general.architecture", architecture, sizeof(architecture)) >= 0 &&
                       (std::string(architecture).find("qwen3") == 0);
    const std::string templ_text(templ);
    if (templ_text.find("<think>") != std::string::npos || qwen3) {
        const size_t open = prompt.rfind("<think>");
        const bool ends_with_open = open != std::string::npos &&
            prompt.find_first_not_of(" \r\n\t", open + 7) == std::string::npos;
        prompt += ends_with_open ? "\n\n</think>\n\n" : "<think>\n\n</think>\n\n";
    }
    return prompt;
}

void clear_model() {
    g_cached_prompt.clear();
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_ctx = nullptr;
    g_model = nullptr;
    g_path.clear();
    g_nctx = 0;
    g_prompt_tps = g_gen_tps = 0;
    g_kv_bytes = -1;
}

template<class F> jstring guarded(JNIEnv *env, F function) {
    try { return function(); }
    catch (const std::exception &exception) { return error(env, exception.what()); }
    catch (...) { return error(env, "Unknown native engine error"); }
}

bool parse_int(const std::string &text, int &result) {
    if (text.empty()) return false;
    char *end = nullptr;
    long value = std::strtol(text.c_str(), &end, 10);
    if (*end || value < 1 || value > 1000000) return false;
    result = static_cast<int>(value);
    return true;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring path,
                                               jint nctx, jint batch, jint ubatch, jint threads) {
    return guarded(env, [&]() -> jstring {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_abort = false;
        clear_model();
        if (nctx < 512 || nctx > 2048 || threads < 1 || threads > 8 || batch < 1 || batch > nctx ||
            ubatch < 1 || ubatch > batch)
            return error(env, "Invalid native load parameters (context 512..2048, threads 1..8, batch bounds)");
        std::call_once(g_backend_once, [] { llama_backend_init(); });
        g_path = jstr(env, path);
        auto model_params = llama_model_default_params();
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        g_model = llama_model_load_from_file(g_path.c_str(), model_params);
        if (!g_model) { clear_model(); return error(env, "llama.cpp could not load model; verify local GGUF path and compatibility"); }
        auto context_params = llama_context_default_params();
        context_params.n_ctx = nctx;
        context_params.n_batch = batch;
        context_params.n_ubatch = ubatch;
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        context_params.abort_callback = [](void *) { return g_abort.load(); };
        g_ctx = llama_init_from_model(g_model, context_params);
        if (!g_ctx) { clear_model(); return error(env, "llama.cpp could not create inference context"); }
        g_nctx = nctx;
        return nullptr;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = true;
    clear_model();
}

extern "C" JNIEXPORT void JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeCancel(JNIEnv *, jobject) { g_abort = true; }

extern "C" JNIEXPORT jint JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeCountTokens(JNIEnv *env, jobject, jstring text) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_model) return -1;
        return static_cast<jint>(tokenize(jstr(env, text), false).size());
    } catch (...) { return -1; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeStats(JNIEnv *env, jobject) {
    return guarded(env, [&]() -> jstring {
        std::lock_guard<std::mutex> lock(g_mutex);
        int64_t heap = -1;
#if defined(__ANDROID__)
        struct mallinfo mi = mallinfo();
        heap = mi.uordblks;
#endif
        char output[2048];
        std::snprintf(output, sizeof(output), "%s\t%d\t%.5f\t%.5f\t%lld\t%lld", g_path.c_str(), g_nctx,
                      g_prompt_tps, g_gen_tps, static_cast<long long>(g_kv_bytes), static_cast<long long>(heap));
        return jstring_utf8(env, output);
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_im_mrgreen_engine_LlamaEngine_nativeGenerate(JNIEnv *env, jobject, jobjectArray messages,
                                                    jobjectArray options, jobject callback) {
    return guarded(env, [&]() -> jstring {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_ctx || !g_model) return error(env, "No local model is loaded");
        g_abort = false;
        const jsize option_count = env->GetArrayLength(options);
        std::vector<std::string> opts;
        for (jsize i = 0; i < option_count; ++i) {
            jstring value = static_cast<jstring>(env->GetObjectArrayElement(options, i));
            opts.push_back(jstr(env, value));
            env->DeleteLocalRef(value);
        }
        const auto value = [&](int index, const char *fallback) -> const std::string & {
            static const std::string default_value;
            return index < static_cast<int>(opts.size()) ? opts[index] : default_value;
        };
        int max_tokens = 256;
        if (opts.size() > 0 && !parse_int(opts[0], max_tokens)) max_tokens = 256;
        max_tokens = std::clamp(max_tokens, 1, 2048);
        float temperature = 0.7f, top_p = 0.8f, min_p = 0.0f, presence = 1.5f;
        int top_k = 20;
        if (opts.size() > 1) temperature = std::strtof(opts[1].c_str(), nullptr);
        if (opts.size() > 2) top_k = std::atoi(opts[2].c_str());
        if (opts.size() > 3) top_p = std::strtof(opts[3].c_str(), nullptr);
        if (opts.size() > 4) min_p = std::strtof(opts[4].c_str(), nullptr);
        if (opts.size() > 5) presence = std::strtof(opts[5].c_str(), nullptr);
        if (!std::isfinite(temperature)) temperature = 0.7f;
        if (!std::isfinite(top_p)) top_p = 0.8f;
        if (!std::isfinite(min_p)) min_p = 0.0f;
        if (!std::isfinite(presence)) presence = 1.5f;
        top_k = std::max(1, top_k);
        std::string prompt = make_prompt(env, messages);
        std::vector<llama_token> tokens = tokenize(prompt, true);
        if (tokens.empty()) return error(env, "Prompt tokenization failed");
        if (tokens.size() + 1 > static_cast<size_t>(g_nctx))
            return error(env, "Prompt exceeds context length; shorten the message");
        max_tokens = std::min(max_tokens, g_nctx - static_cast<int>(tokens.size()));
        if (max_tokens < 1) return error(env, "Prompt exceeds context length");

        llama_memory_t memory = llama_get_memory(g_ctx);
        size_t common = 0;
        while (common < tokens.size() && common < g_cached_prompt.size() && tokens[common] == g_cached_prompt[common]) ++common;
        // KV memory does not retain the previous prompt's logits after generation. Keep
        // prefix KV only and re-decode at least the last prompt token for fresh logits.
        common = std::min(common, tokens.size() - 1);
        size_t position = 0;
        bool reused = false;
        if (common > 0) {
            if (llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1)) {
                position = common;
                reused = true;
            } else {
                // Hybrid/recurrent memories may reject a partial sequence removal.
                llama_memory_clear(memory, true);
                g_cached_prompt.clear();
                common = 0;
            }
        } else {
            llama_memory_clear(memory, true);
            g_cached_prompt.clear();
        }
        auto prompt_start = std::chrono::steady_clock::now();
        std::vector<llama_token> suffix(tokens.begin() + position, tokens.end());
        int rc = decode_tokens(suffix, static_cast<int>(llama_n_batch(g_ctx)), position);
        if (rc || g_abort) {
            llama_memory_clear(memory, true);
            g_cached_prompt.clear();
            return error(env, g_abort ? "Generation cancelled" : "Prompt evaluation failed in llama_decode");
        }
        // Update only after a complete successful prompt decode; failed/cancelled work cannot be reused.
        g_cached_prompt = tokens;
        const double prompt_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - prompt_start).count();
        g_prompt_tps = prompt_seconds > 0 ? suffix.size() / prompt_seconds : 0;
        (void)reused; // Reuse is deliberately internal; KV byte stats remain unavailable (-1).

        using SamplerPtr = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        SamplerPtr sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), &llama_sampler_free);
        if (!sampler) { llama_memory_clear(memory, true); g_cached_prompt.clear(); return error(env, "Sampler initialization failed"); }
        if (opts.size() > 6 && !opts[6].empty()) {
            llama_sampler *grammar = llama_sampler_init_grammar(vocab(), opts[6].c_str(), "root");
            if (!grammar) { llama_memory_clear(memory, true); g_cached_prompt.clear(); return error(env, "Invalid GBNF grammar"); }
            llama_sampler_chain_add(sampler.get(), grammar);
        }
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab()), 64, 1.0f, 0.0f, presence));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(static_cast<uint32_t>(std::chrono::steady_clock::now().time_since_epoch().count())));
        std::vector<std::string> stops;
        if (opts.size() > 7) {
            size_t begin = 0;
            while (begin < opts[7].size()) {
                size_t end = opts[7].find('\1', begin);
                stops.push_back(opts[7].substr(begin, end == std::string::npos ? end : end - begin));
                if (end == std::string::npos) break;
                begin = end + 1;
            }
        }
        size_t stop_hold = 0;
        for (const auto &stop : stops) stop_hold = std::max(stop_hold, stop.size());
        Utf8Emitter emitter(env, callback);
        auto generation_start = std::chrono::steady_clock::now();
        int generated = 0;
        std::string held;
        bool stopped = false;
        for (; generated < max_tokens && !g_abort; ) {
            llama_token token = llama_sampler_sample(sampler.get(), g_ctx, -1);
            if (llama_vocab_is_eog(vocab(), token)) break;
            llama_sampler_accept(sampler.get(), token);
            llama_batch one = llama_batch_init(1, 0, 1);
            one.token[0] = token;
            one.pos[0] = static_cast<llama_pos>(tokens.size() + generated);
            one.n_seq_id[0] = 1;
            one.seq_id[0][0] = 0;
            one.logits[0] = true;
            rc = llama_decode(g_ctx, one);
            llama_batch_free(one);
            if (rc) break;
            ++generated;
            char piece[512];
            int piece_size = llama_token_to_piece(vocab(), token, piece, sizeof(piece), 0, false);
            if (piece_size <= 0) continue;
            held.append(piece, static_cast<size_t>(piece_size));
            size_t stop_at = std::string::npos;
            for (const auto &stop : stops) if (!stop.empty()) {
                size_t found = held.find(stop);
                if (found != std::string::npos) stop_at = std::min(stop_at, found);
            }
            if (stop_at != std::string::npos) {
                if (!emitter.push(held.substr(0, stop_at), true)) {
                    llama_memory_clear(memory, true);
                    g_cached_prompt.clear();
                    return nullptr; // Do not make JNI calls while a Java exception is pending.
                }
                stopped = true;
                break;
            }
            const size_t safe = stop_hold ? (held.size() >= stop_hold ? held.size() - stop_hold + 1 : 0) : held.size();
            if (safe) {
                std::string ready = held.substr(0, safe);
                held.erase(0, safe);
                if (!emitter.push(ready)) {
                    llama_memory_clear(memory, true);
                    g_cached_prompt.clear();
                    return nullptr; // Preserve a pending Java callback exception.
                }
            }
        }
        if (!stopped && !emitter.push(held, true)) {
            llama_memory_clear(memory, true);
            g_cached_prompt.clear();
            return nullptr;
        }
        const double generation_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - generation_start).count();
        g_gen_tps = generation_seconds > 0 ? generated / generation_seconds : 0;
        if (g_abort || rc) {
            llama_memory_clear(memory, true);
            g_cached_prompt.clear();
            return error(env, g_abort ? "Generation cancelled" : "Token evaluation failed in llama_decode");
        }
        return nullptr;
    });
}
