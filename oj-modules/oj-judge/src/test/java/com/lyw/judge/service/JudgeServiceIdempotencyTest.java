package com.lyw.judge.service;

import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.UserSubmit;
import com.lyw.judge.service.impl.JudgeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JudgeServiceIdempotencyTest {
    private final JudgeServiceImpl service = new JudgeServiceImpl();
    private final UserSubmitStore store = mock(UserSubmitStore.class);
    private final ISandboxPoolService sandbox = mock(ISandboxPoolService.class);
    private final StateCacheService cache = mock(StateCacheService.class);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "userSubmitStore", store);
        ReflectionTestUtils.setField(service, "sandboxPoolService", sandbox);
        ReflectionTestUtils.setField(service, "stateCacheService", cache);
    }

    @Test
    void existingResultSkipsSandboxAndSurvivesCacheFailure() {
        when(store.findCompleted(any())).thenReturn(saved(0));
        doThrow(new IllegalStateException("缓存不可用")).when(cache).update(anyString(), any(), anyLong(), any());
        UserQuestionResultVO result = service.doJudgeJavaCode(submission());
        assertEquals(JudgeResultState.JUDGED, result.getResultState());
        assertEquals(0, result.getPass());
        verifyNoInteractions(sandbox);
        verify(store, never()).saveOrReuse(any(), any());
    }

    @Test
    void systemResultsNeverInsert() {
        for (SandBoxExecuteResult result : Arrays.asList(null, SandBoxExecuteResult.fail(CodeRunStatus.SYSTEM_BUSY),
                SandBoxExecuteResult.fail(CodeRunStatus.UNKNOWN_FAILED), SandBoxExecuteResult.fail(CodeRunStatus.RUNNING))) {
            when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenReturn(result);
            assertThrows(JudgeSystemException.class, () -> service.doJudgeJavaCode(submission()));
        }
        verify(store, never()).saveOrReuse(any(), any());
        verifyNoInteractions(cache);
    }

    @Test
    void dockerExceptionNeverInsert() {
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenThrow(new IllegalStateException("Docker 断开"));
        assertThrows(JudgeSystemException.class, () -> service.doJudgeJavaCode(submission()));
        verify(store, never()).saveOrReuse(any(), any());
    }

    @Test
    void compilationFailureIsARealResult() {
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenReturn(SandBoxExecuteResult.fail(CodeRunStatus.COMPILE_FAILED));
        when(store.saveOrReuse(any(), any())).thenReturn(saved(0));
        service.doJudgeJavaCode(submission());
        ArgumentCaptor<UserQuestionResultVO> result = ArgumentCaptor.forClass(UserQuestionResultVO.class);
        verify(store).saveOrReuse(any(), result.capture());
        assertEquals(10001L, result.getValue().getSubmitId());
        assertEquals(JudgeResultState.JUDGED, result.getValue().getResultState());
        assertEquals(0, result.getValue().getPass());
    }

    @Test
    void cacheUsesDatabaseWinnerInsteadOfCurrentComputation() {
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenReturn(
                SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of("3"), 1L, 1L));
        when(store.saveOrReuse(any(), any())).thenReturn(saved(0));
        assertEquals(0, service.doJudgeJavaCode(submission()).getPass());
        ArgumentCaptor<JudgeResultCache> captured = ArgumentCaptor.forClass(JudgeResultCache.class);
        verify(cache).update(anyString(), captured.capture(), anyLong(), any());
        assertEquals(0, captured.getValue().getResult().getPass());
    }

    @Test
    void failedInitialDatabaseReadDoesNotRunSandbox() {
        when(store.findCompleted(any())).thenThrow(new IllegalStateException("数据库不可用"));
        assertThrows(IllegalStateException.class, () -> service.doJudgeJavaCode(submission()));
        verifyNoInteractions(sandbox);
    }

    @Test
    void completedProgramCanFailQuestionTimeLimitWithoutBecomingSystemFailure() {
        JudgeSubmitDTO request = submission();
        request.setTimeLimit(10L);
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenReturn(
                SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of("3"), 1L, 20L));
        when(store.saveOrReuse(any(), any())).thenReturn(saved(0));
        service.doJudgeJavaCode(request);
        ArgumentCaptor<UserQuestionResultVO> recorded = ArgumentCaptor.forClass(UserQuestionResultVO.class);
        verify(store).saveOrReuse(eq(request), recorded.capture());
        assertEquals(0, recorded.getValue().getPass());
        assertEquals(CodeRunStatus.OUT_OF_TIME.getMsg(), recorded.getValue().getExeMessage());
        verify(sandbox).exeJavaCode(eq(7L), anyString(), anyList(), eq(10L));
    }

    public static JudgeSubmitDTO submission() {
        JudgeSubmitDTO dto = new JudgeSubmitDTO();
        dto.setSubmitId(10001L);
        dto.setUserId(7L);
        dto.setQuestionId(9L);
        dto.setProgramType(0);
        dto.setUserCode("public class Solution {}");
        dto.setInputList(List.of("1 2"));
        dto.setOutputList(List.of("3"));
        dto.setTimeLimit(1000L);
        dto.setSpaceLimit(1000000L);
        dto.setDifficulty(1);
        return dto;
    }

    private UserSubmit saved(int pass) {
        UserSubmit row = new UserSubmit();
        row.setSubmitId(10001L);
        row.setPass(pass);
        row.setScore(pass == 1 ? 10 : 0);
        row.setExeMessage(pass == 1 ? "运行成功" : "未通过所有用例");
        row.setCaseJudgeRes("[]");
        return row;
    }
}
