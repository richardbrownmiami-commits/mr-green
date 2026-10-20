#include <jni.h>
#include <string.h>
#include "NAR.h"
#define MAX_SENTENCE 512
#define MAX_CYCLES 128

/* Only generated bounded ASCII Narsese from the private Kotlin grammar enters here. */
static int copy_input(JNIEnv *env, jstring text, char out[MAX_SENTENCE]) {
    if (!text) return 0;
    const char *s = (*env)->GetStringUTFChars(env, text, 0);
    if (!s) return 0;
    size_t n = strlen(s);
    int ok = n > 0 && n < MAX_SENTENCE;
    for (size_t i = 0; ok && i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c < 32 || c > 126) ok = 0;
    }
    if (ok) memcpy(out, s, n + 1);
    (*env)->ReleaseStringUTFChars(env, text, s);
    return ok;
}
JNIEXPORT jboolean JNICALL Java_im_mrgreen_reasoning_OnaNative_reset(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    NAR_INIT();
    PRINT_INPUT = false;
    PRINT_DERIVATIONS = false;
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_im_mrgreen_reasoning_OnaNative_add(JNIEnv *env, jobject thiz, jstring text) {
    (void)thiz;
    char input[MAX_SENTENCE];
    if (!copy_input(env, text, input)) return JNI_FALSE;
    size_t n = strlen(input);
    if (input[n - 1] != '.') return JNI_FALSE; /* Never accept motor goals or commands. */
    NAR_AddInputNarsese(input);
    memset(input, 0, sizeof(input));
    return JNI_TRUE;
}
JNIEXPORT jstring JNICALL Java_im_mrgreen_reasoning_OnaNative_query(JNIEnv *env, jobject thiz, jstring text, jint cycles) {
    (void)thiz;
    char input[MAX_SENTENCE];
    if (cycles < 0 || cycles > MAX_CYCLES || !copy_input(env, text, input)) return NULL;
    size_t n = strlen(input);
    if (input[n - 1] != '?' || !NAR_AnswerGround(input, cycles)) {
        memset(input, 0, sizeof(input));
        return NULL;
    }
    input[n - 1] = 0; /* This exact term matched the core's real inferred belief. */
    jstring answer = (*env)->NewStringUTF(env, input);
    memset(input, 0, sizeof(input));
    return answer;
}
