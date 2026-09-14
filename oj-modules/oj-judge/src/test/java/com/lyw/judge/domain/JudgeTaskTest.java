package com.lyw.judge.domain;

import com.lyw.common.core.enums.CodeRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeTaskTest {

    @Test
    void taskCanCompleteOnlyOnceAndExposesState() {
        JudgeTask task = newTask();

        assertTrue(task.transition(JudgeTaskState.CREATED, JudgeTaskState.QUEUED));
        assertTrue(task.transition(JudgeTaskState.QUEUED, JudgeTaskState.RUNNING));
        assertTrue(task.complete(SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L),
                JudgeTaskState.SUCCEEDED));
        assertFalse(task.complete(SandBoxExecuteResult.fail(CodeRunStatus.FAILED), JudgeTaskState.FAILED));
        assertEquals(JudgeTaskState.SUCCEEDED, task.getState());
        assertEquals(CodeRunStatus.SUCCEED, task.getFuture().join().getRunStatus());
    }

    @Test
    void rejectedTaskCannotBeCompletedAgain() {
        JudgeTask task = newTask();

        assertTrue(task.transition(JudgeTaskState.CREATED, JudgeTaskState.QUEUED));
        assertTrue(task.reject(SandBoxExecuteResult.fail(CodeRunStatus.SYSTEM_BUSY)));
        assertFalse(task.transition(JudgeTaskState.QUEUED, JudgeTaskState.RUNNING));
        assertFalse(task.complete(SandBoxExecuteResult.fail(CodeRunStatus.FAILED), JudgeTaskState.FAILED));
        assertEquals(JudgeTaskState.REJECTED, task.getState());
        assertEquals(CodeRunStatus.SYSTEM_BUSY, task.getFuture().join().getRunStatus());
    }

    private JudgeTask newTask() {
        return new JudgeTask(1L, "class Main {}", List.of(""),
                containerId -> SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L), null);
    }
}
