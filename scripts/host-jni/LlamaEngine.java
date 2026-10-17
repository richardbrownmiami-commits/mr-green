package im.mrgreen.engine;

/** Executes the real JNI bridge on a host, not an Android or ARM emulator. */
public final class LlamaEngine {
    static { System.loadLibrary("mrgreen"); }
    private native String nativeLoad(String path, int nctx, int batch, int ubatch, int threads);
    private native void nativeUnload();
    private native void nativeCancel();
    private native String nativeGenerate(String[] messages, String[] options, Callback callback);
    interface Callback {
        void onToken(String text);
        void onProgress(String stage, int completed, int total);
    }
    static final class Events implements Callback {
        int tokens, promptProgress, generatedProgress;
        final LlamaEngine engine;
        final int cancelAfter;
        Events(LlamaEngine e, int c) { engine = e; cancelAfter = c; }
        public void onToken(String text) {
            tokens++;
            if (cancelAfter > 0 && tokens == cancelAfter) engine.nativeCancel();
        }
        public void onProgress(String stage, int done, int total) {
            if ("prompt".equals(stage)) {
                promptProgress++;
                if (cancelAfter == -1 && done == 0) engine.nativeCancel();
            }
            if ("generate".equals(stage)) generatedProgress++;
        }
    }
    static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("usage: MODEL.gguf");
        LlamaEngine e = new LlamaEngine();
        String err = e.nativeLoad(args[0], 512, 128, 64, 2);
        check(err == null, "load; error=" + err);
        try {
            String[] messages = {"user", "Reply with a short numbered list of five colors."};
            String[] options = {"48", "0.0", "1", "1.0", "0.0", "0.0", "", ""};
            Events first = new Events(e, 0);
            err = e.nativeGenerate(messages, options, first);
            check(err == null && first.tokens > 0 && first.promptProgress > 0 && first.generatedProgress > 0,
                "stream and both progress stages; callbacks=" + first.tokens + "; error=" + err);
            Events cancel = new Events(e, 3);
            err = e.nativeGenerate(new String[]{"user", "Write a long detailed story."}, options, cancel);
            check("Generation cancelled".equals(err) && cancel.tokens == 3, "token cancellation");
            Events promptCancel = new Events(e, -1);
            err = e.nativeGenerate(messages, options, promptCancel);
            check("Generation cancelled".equals(err) && promptCancel.tokens == 0 && promptCancel.promptProgress > 0,
                "prompt-stage cancellation");
            Events repeat = new Events(e, 0);
            err = e.nativeGenerate(messages, options, repeat);
            check(err == null && repeat.tokens > 0, "request after cancellation; callbacks=" + repeat.tokens);
        } finally {
            e.nativeUnload();
        }
        System.out.println("PASS unload; HOST ONLY, no Android phone validation");
    }
}
