package com.lyw.judge.callback;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SandboxOutputCallbackTest {
    @Test
    void combinesSplitUtf8AndKeepsBothChannels() throws Exception {
        SandboxOutputCallback callback = new SandboxOutputCallback(new SandboxOutputBudget(100));
        byte[] text = "中文输出".getBytes(StandardCharsets.UTF_8);
        callback.onNext(new Frame(StreamType.STDOUT, Arrays.copyOfRange(text, 0, 2)));
        callback.onNext(new Frame(StreamType.STDERR, "诊断".getBytes(StandardCharsets.UTF_8)));
        callback.onNext(new Frame(StreamType.STDOUT, Arrays.copyOfRange(text, 2, text.length)));
        callback.onComplete();
        assertTrue(callback.awaitCompletion(10));
        assertEquals("中文输出", callback.stdout());
        assertEquals("诊断", callback.stderr());
        assertTrue(callback.completedNormally());
        callback.close();
    }

    @Test
    void budgetIsSharedAcrossCompilationAndCasesAndStopsBufferGrowth() throws Exception {
        SandboxOutputBudget budget = new SandboxOutputBudget(5);
        SandboxOutputCallback compile = new SandboxOutputCallback(budget);
        compile.onNext(new Frame(StreamType.STDERR, new byte[]{'a', 'b'}));
        compile.close();
        SandboxOutputCallback first = new SandboxOutputCallback(budget);
        first.onNext(new Frame(StreamType.STDOUT, new byte[]{'c', 'd', 'e'}));
        assertFalse(budget.isExceeded());
        assertEquals(5, budget.getAcceptedBytes());
        SandboxOutputCallback second = new SandboxOutputCallback(budget);
        second.onNext(new Frame(StreamType.STDOUT, new byte[]{'f'}));
        assertTrue(budget.isExceeded());
        assertTrue(second.awaitCompletion(10));
        assertFalse(second.completedNormally());
        for (int index = 0; index < 100; index++) second.onNext(new Frame(StreamType.STDERR, new byte[1024]));
        assertEquals("", second.stdout());
        assertEquals("", second.stderr());
        assertEquals(5, budget.getAcceptedBytes());
        first.close(); second.close();
    }

    @Test
    void emptyOutputAndLateStartAreSafe() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        SandboxOutputCallback callback = new SandboxOutputCallback(new SandboxOutputBudget(10));
        assertEquals("", callback.stdout());
        callback.close();
        callback.onStart(closed::incrementAndGet);
        callback.onNext(new Frame(StreamType.STDOUT, new byte[]{'x'}));
        callback.close();
        assertEquals(1, closed.get());
        assertEquals("", callback.stdout());
        assertFalse(callback.hasStarted());
    }

    @Test
    void streamErrorCannotBeOverwrittenByLaterOutputOrCompletion() throws Exception {
        SandboxOutputCallback callback = new SandboxOutputCallback(new SandboxOutputBudget(10));
        IllegalStateException error = new IllegalStateException("连接中断");
        callback.onError(error);
        callback.onNext(new Frame(StreamType.STDOUT, new byte[]{'x'}));
        callback.onComplete();
        assertSame(error, callback.getError());
        assertTrue(callback.awaitCompletion(10));
        callback.close();
    }
}
