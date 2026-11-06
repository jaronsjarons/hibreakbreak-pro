package local.claude.keyboard;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the app on its own thread, in order, so the ~1.6 ms each call takes never holds up the next keypress or
 * the pop-up. Reads (rare: after a cursor jump) wait for the writes queued before them.
 */
final class IcQueue {
    private final Handler handler;

    IcQueue() {
        HandlerThread t = new HandlerThread("ime-ic", Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        handler = new Handler(t.getLooper());
    }

    void commitText(final InputConnection ic, final CharSequence s) {
        handler.post(new Runnable() { public void run() { ic.commitText(s, 1); } });
    }

    void deleteBefore(final InputConnection ic, final int n) {
        handler.post(new Runnable() { public void run() { ic.deleteSurroundingText(n, 0); } });
    }

    /** Group the writes between begin and end into one change (Chrome's address bar undoes a lone delete). */
    void beginBatch(final InputConnection ic) {
        handler.post(new Runnable() { public void run() { ic.beginBatchEdit(); } });
    }

    void endBatch(final InputConnection ic) {
        handler.post(new Runnable() { public void run() { ic.endBatchEdit(); } });
    }

    void key(final InputConnection ic, final int code) {
        handler.post(new Runnable() {
            public void run() {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        });
    }

    void editorAction(final InputConnection ic, final int action) {
        handler.post(new Runnable() { public void run() { ic.performEditorAction(action); } });
    }

    CharSequence textBefore(final InputConnection ic, final int n) { return read(ic, n, true); }

    CharSequence textAfter(final InputConnection ic, final int n) { return read(ic, n, false); }

    private CharSequence read(final InputConnection ic, final int n, final boolean beforeCursor) {
        final CharSequence[] out = new CharSequence[1];
        final CountDownLatch done = new CountDownLatch(1);
        handler.post(new Runnable() {
            public void run() {
                try { out[0] = beforeCursor ? ic.getTextBeforeCursor(n, 0) : ic.getTextAfterCursor(n, 0); }
                finally { done.countDown(); }
            }
        });
        try { done.await(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return out[0];
    }
}
