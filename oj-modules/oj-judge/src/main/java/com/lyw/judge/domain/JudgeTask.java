package com.lyw.judge.domain;

import lombok.Getter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 容器池内部统一处理的判题任务。
 */
@Getter
public final class JudgeTask {

    private final String taskId = UUID.randomUUID().toString();
    private final Long userId;
    private final String userCode;
    private final List<String> inputList;
    private final Function<String, SandBoxExecuteResult> executor;
    private final CompletableFuture<SandBoxExecuteResult> future = new CompletableFuture<>();
    private final Consumer<SandBoxExecuteResult> completionHandler;
    private final AtomicReference<JudgeTaskState> state =
            new AtomicReference<>(JudgeTaskState.CREATED);

    public JudgeTask(Long userId, String userCode, List<String> inputList,
                     Function<String, SandBoxExecuteResult> executor,
                     Consumer<SandBoxExecuteResult> completionHandler) {
        this.userId = userId;
        this.userCode = userCode;
        this.inputList = List.copyOf(inputList);
        this.executor = executor;
        this.completionHandler = completionHandler;
    }

    public SandBoxExecuteResult execute(String containerId) {
        return executor.apply(containerId);
    }

    public boolean transition(JudgeTaskState expected, JudgeTaskState target) {
        return state.compareAndSet(expected, target);
    }

    /** 返回任务当前状态的快照，避免调用方直接操作内部原子引用。 */
    public JudgeTaskState getState() {
        return state.get();
    }

    public boolean complete(SandBoxExecuteResult result, JudgeTaskState terminalState) {
        if (!state.compareAndSet(JudgeTaskState.RUNNING, terminalState)) {
            return false;
        }
        future.complete(result);
        if (completionHandler != null) {
            try { completionHandler.accept(result); } catch (RuntimeException ignored) { }
        }
        return true;
    }

    public boolean reject(SandBoxExecuteResult result) {
        if (!state.compareAndSet(JudgeTaskState.QUEUED, JudgeTaskState.REJECTED)) {
            return false;
        }
        future.complete(result);
        if (completionHandler != null) {
            try { completionHandler.accept(result); } catch (RuntimeException ignored) { }
        }
        return true;
    }
}
