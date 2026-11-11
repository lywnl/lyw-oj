package com.lyw.judge.callback;

import com.github.dockerjava.api.async.ResultCallback;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 回调只记录事件，关闭流和回收容器由 Worker 完成。 */
public abstract class SandboxStreamCallback<T> implements ResultCallback<T> {
    private final CountDownLatch startedSignal = new CountDownLatch(1);
    private final CountDownLatch finishedSignal = new CountDownLatch(1);
    private Closeable stream;
    private volatile boolean started;
    private volatile boolean completedNormally;
    private volatile boolean closed;
    private volatile Throwable error;

    @Override
    public void onStart(Closeable handle) {
        synchronized (this) {
            if (!closed) {
                stream = handle;
                started = handle != null;
                startedSignal.countDown();
                return;
            }
        }
        // 启动超时后仍可能收到响应，迟到句柄也必须立即关闭。
        if (handle != null) {
            try { handle.close(); } catch (IOException e) { error = e; }
        }
        startedSignal.countDown();
    }

    @Override
    public void onError(Throwable throwable) {
        synchronized (this) {
            if (closed) return;
            if (error == null) error = throwable;
        }
        startedSignal.countDown();
        finishedSignal.countDown();
    }

    @Override
    public void onComplete() {
        synchronized (this) {
            if (closed) return;
            completedNormally = true;
        }
        finishedSignal.countDown();
    }

    protected final void wakeWaiter() { finishedSignal.countDown(); }
    public final boolean hasStarted() { return started; }
    public final boolean completedNormally() { return completedNormally; }
    public final Throwable getError() { return error; }
    public final boolean isClosed() { return closed; }

    public final boolean awaitStarted(long timeoutMs) throws InterruptedException {
        return startedSignal.await(timeoutMs, TimeUnit.MILLISECONDS) && started && error == null;
    }

    public final boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        return finishedSignal.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public final void close() throws IOException {
        Closeable handle;
        synchronized (this) {
            if (closed) return;
            closed = true;
            handle = stream;
            stream = null;
        }
        if (handle != null) handle.close();
    }
}
