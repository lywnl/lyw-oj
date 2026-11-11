package com.lyw.judge.callback;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class SandboxOutputCallback extends SandboxStreamCallback<Frame> {
    private final SandboxOutputBudget budget;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    public SandboxOutputCallback(SandboxOutputBudget budget) {
        this.budget = budget;
    }

    @Override
    public synchronized void onNext(Frame frame) {
        if (isClosed() || frame == null || frame.getPayload() == null) return;
        byte[] payload = frame.getPayload();
        int accepted = budget.accept(payload.length);
        ByteArrayOutputStream target = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
        target.write(payload, 0, accepted);
        if (budget.isExceeded()) wakeWaiter();
    }

    public synchronized String stdout() { return stdout.toString(StandardCharsets.UTF_8); }
    public synchronized String stderr() { return stderr.toString(StandardCharsets.UTF_8); }
}
