package com.lyw.friend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.controller.user.UserQuestionController;
import com.lyw.friend.domain.question.es.QuestionES;
import com.lyw.friend.domain.user.UserSubmit;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.elasticsearch.QuestionRepository;
import com.lyw.friend.mapper.user.UserSubmitMapper;
import com.lyw.friend.rabbit.JudgeProducer;
import com.lyw.friend.service.user.impl.UserQuestionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserQuestionResultTest {
    private final UserQuestionServiceImpl service = new UserQuestionServiceImpl();
    private final StateCacheService cache = mock(StateCacheService.class);
    private final UserSubmitMapper mapper = mock(UserSubmitMapper.class);
    private final JudgeProducer producer = mock(JudgeProducer.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);

    @BeforeEach
    void setup() {
        ThreadLocalUtil.set(Constants.USER_ID, 7L);
        ReflectionTestUtils.setField(service, "stateCacheService", cache);
        ReflectionTestUtils.setField(service, "userSubmitMapper", mapper);
        ReflectionTestUtils.setField(service, "judgeProducer", producer);
        ReflectionTestUtils.setField(service, "questionRepository", questions);
    }

    @AfterEach
    void cleanup() { ThreadLocalUtil.remove(); }

    @Test
    void ordinaryWaitingErrorAndMissingNeverQueryDatabase() {
        when(cache.get(anyString(), eq(JudgeResultCache.class))).thenReturn(
                JudgeResultCache.waiting(7L, 10001L), JudgeResultCache.error(7L, 10001L, "系统繁忙"), null);
        assertEquals(JudgeResultState.NO_RESULT, service.getExeResult(10001L, false).getResultState());
        UserQuestionResultVO error = service.getExeResult(10001L, false);
        assertEquals(JudgeResultState.SYSTEM_ERROR, error.getResultState());
        assertNull(error.getPass());
        assertEquals(JudgeResultState.NO_RESULT, service.getExeResult(10001L, false).getResultState());
        verifyNoInteractions(mapper);
        verify(cache, never()).update(anyString(), any(), anyLong(), any());
    }

    @Test
    void verificationFindsRealResultDespiteErrorCacheAndFailedRefill() {
        when(cache.get(anyString(), eq(JudgeResultCache.class))).thenReturn(JudgeResultCache.error(7L, 10001L, "临时错误"));
        when(mapper.selectUserSubmit(7L, 10001L)).thenReturn(saved());
        doThrow(new IllegalStateException("Redis 离线")).when(cache).update(anyString(), any(), anyLong(), any());
        UserQuestionResultVO result = service.getExeResult(10001L, true);
        assertEquals(JudgeResultState.JUDGED, result.getResultState());
        assertEquals(1, result.getPass());
        verify(mapper).selectUserSubmit(7L, 10001L);
    }

    @Test
    void redisReadFailureCanReturnDatabaseResult() {
        when(cache.get(anyString(), eq(JudgeResultCache.class))).thenThrow(new IllegalStateException("Redis 超时"));
        when(mapper.selectUserSubmit(7L, 10001L)).thenReturn(saved());
        assertEquals(JudgeResultState.JUDGED, service.getExeResult(10001L, false).getResultState());
    }

    @Test
    void unreadableCacheAndAbsentDatabaseAreQueryFailure() {
        when(cache.get(anyString(), eq(JudgeResultCache.class))).thenThrow(new IllegalStateException("Redis 超时"));
        ServiceException failure = assertThrows(ServiceException.class, () -> service.getExeResult(10001L, false));
        assertEquals(ResultCode.ERROR, failure.getResultCode());
    }

    @Test
    void databaseFailureIsNotNoResult() {
        when(mapper.selectUserSubmit(7L, 10001L)).thenThrow(new DataAccessResourceFailureException("数据库离线"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.getExeResult(10001L, true));
    }

    @Test
    void judgedCacheAvoidsDatabaseEvenDuringVerification() {
        UserQuestionResultVO result = UserQuestionResultVO.noResult(10001L);
        result.setResultState(JudgeResultState.JUDGED);
        result.setPass(1);
        when(cache.get(anyString(), eq(JudgeResultCache.class))).thenReturn(JudgeResultCache.judged(7L, result));
        assertSame(result, service.getExeResult(10001L, true));
        verifyNoInteractions(mapper);
    }

    @Test
    void foreignCacheCannotBeReturned() {
        when(cache.get(anyString(), eq(JudgeResultCache.class))).thenReturn(JudgeResultCache.error(8L, 10001L, "其他用户"));
        assertThrows(ServiceException.class, () -> service.getExeResult(10001L, false));
        verify(mapper).selectUserSubmit(7L, 10001L);
    }

    @Test
    void missingLoginCannotReadCacheOrDatabase() {
        ThreadLocalUtil.remove();
        assertEquals(ResultCode.FAILED_UNAUTHORIZED,
                assertThrows(ServiceException.class, () -> service.getExeResult(10001L, false)).getResultCode());
        verifyNoInteractions(cache, mapper);
    }

    @Test
    void failedSendKeepsGeneratedIdAndSerializesItAsString() throws Exception {
        QuestionES question = new QuestionES();
        question.setQuestionId(9L);
        question.setMainFuc("");
        question.setQuestionCase("[{\"input\":\"1 2\",\"output\":\"3\"}]");
        when(questions.findById(9L)).thenReturn(Optional.of(question));
        doThrow(new IllegalStateException("发送异常")).when(producer).produceMsg(any());
        UserQuestionSubmitDTO request = new UserQuestionSubmitDTO();
        request.setQuestionId(9L);
        request.setProgramType(0);
        request.setUserCode("public class Solution {}");
        var response = service.rabbitSubmit(request);
        assertEquals(3701, response.getCode());
        assertNotNull(response.getData().getSubmitId());
        ArgumentCaptor<JudgeSubmitDTO> sent = ArgumentCaptor.forClass(JudgeSubmitDTO.class);
        verify(producer).produceMsg(sent.capture());
        assertEquals(response.getData().getSubmitId(), sent.getValue().getSubmitId());
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertTrue(json.path("data").path("submitId").isTextual());
        verify(cache, times(2)).update(anyString(), any(JudgeResultCache.class), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void controllerRejectsMissingMalformedAndOverflowIds() {
        UserQuestionController controller = new UserQuestionController();
        ReflectionTestUtils.setField(controller, "userQuestionService", service);
        for (String id : new String[]{null, "0", "-1", "abc", "9223372036854775808"}) {
            assertEquals(3002, controller.getExeResult(id, false).getCode());
        }
        verifyNoInteractions(cache, mapper);
    }

    private UserSubmit saved() {
        UserSubmit row = new UserSubmit();
        row.setSubmitId(10001L);
        row.setPass(1);
        row.setScore(10);
        row.setExeMessage("运行成功");
        row.setCaseJudgeRes("[]");
        return row;
    }
}
