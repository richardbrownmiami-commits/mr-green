package im.mrgreen.companion;

import android.app.Service;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/** Harmless protocol demonstration; host app must independently review and confirm every invocation. */
public final class SampleCompanionService extends Service {
    static final int HELLO=10, INVOKE=12, RESULT=13;
    private final Messenger endpoint = new Messenger(new Handler(msg -> {
        Messenger reply = msg.replyTo;
        if (reply == null) return true;
        Bundle b = new Bundle();
        b.putString("nonce", msg.getData().getString("nonce", ""));
        if (msg.what == HELLO && msg.getData().getInt("protocol_version", -1) == 1) {
            b.putInt("protocol_version", 1);
            Bundle tool = new Bundle(); tool.putString("name", "demo.echo"); tool.putString("description", "Return the supplied text (demonstration only)");
            b.putParcelableArray("tools", new Bundle[]{tool});
        } else if (msg.what == INVOKE && "demo.echo".equals(msg.getData().getString("name"))) {
            String args = msg.getData().getString("args", "{}");
            b.putString("result", args.length() <= 16384 ? args : "{}");
        } else { b.putString("result", "unsupported request"); }
        Message response=Message.obtain(null, RESULT); response.setData(b);
        try { reply.send(response); } catch (RemoteException ignored) { }
        return true;
    }));
    @Override public IBinder onBind(android.content.Intent intent) { return endpoint.getBinder(); }
}
