// Host-only llama.cpp regression for the zero-token batch failure.
// Build/link against the pinned vendor llama library; run with one GGUF path.
#include "llama.h"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

int main(int argc, char **argv) {
    if (argc != 2) { std::fprintf(stderr, "usage: %s MODEL.gguf\n", argv[0]); return 2; }
    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model *model = llama_model_load_from_file(argv[1], mp);
    if (!model) { std::fprintf(stderr, "model load failed\n"); return 3; }
    auto cp = llama_context_default_params();
    cp.n_ctx = 256; cp.n_batch = 64; cp.n_ubatch = 64; cp.n_threads = 2; cp.n_threads_batch = 2;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { std::fprintf(stderr, "context init failed\n"); llama_model_free(model); return 4; }

    llama_batch empty = llama_batch_init(1, 0, 1);
    empty.n_tokens = 0; // Old buggy caller did not populate a token batch.
    const int empty_rc = llama_decode(ctx, empty);
    llama_batch_free(empty);

    const llama_vocab *vocab = llama_model_get_vocab(model);
    std::string prompt = "Once upon a time";
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.data(), (int)prompt.size(), tokens.data(), (int)tokens.size(), true, false);
    if (n < 0) { std::fprintf(stderr, "prompt tokenize failed (%d)\n", n); return 5; }
    llama_batch batch = llama_batch_init(n, 0, 1);
    batch.n_tokens = n;
    for (int i=0; i<n; ++i) { batch.token[i]=tokens[i]; batch.pos[i]=i; batch.n_seq_id[i]=1; batch.seq_id[i][0]=0; batch.logits[i]=(i==n-1); }
    const int prompt_rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    int generated = 0;
    if (prompt_rc == 0) {
        int pos = n;
        for (; generated < 8; ++generated, ++pos) {
            float *logits = llama_get_logits_ith(ctx, -1);
            if (!logits) break;
            int best = 0;
            for (int j=1; j<llama_vocab_n_tokens(vocab); ++j) if (logits[j] > logits[best]) best=j;
            llama_token tok = best;
            llama_batch one = llama_batch_init(1, 0, 1);
            one.n_tokens=1; one.token[0]=tok; one.pos[0]=pos; one.n_seq_id[0]=1; one.seq_id[0][0]=0; one.logits[0]=true;
            int rc=llama_decode(ctx, one); llama_batch_free(one);
            if (rc != 0) break;
        }
    }
    std::printf("empty_batch_n_tokens=0 empty_decode_rc=%d\n", empty_rc);
    std::printf("nonempty_batch_n_tokens=%d prompt_decode_rc=%d generated_tokens=%d\n", n, prompt_rc, generated);
    llama_free(ctx); llama_model_free(model); llama_backend_free();
    return (empty_rc != 0 && n > 0 && prompt_rc == 0 && generated > 0) ? 0 : 6;
}
