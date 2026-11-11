package com.lyw.friend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.api.service.RemoteJudgeService;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.common.security.handler.GlobalExceptionHandler;
import com.lyw.friend.controller.user.UserQuestionController;
import com.lyw.friend.domain.question.Question;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.mapper.question.QuestionMapper;
import com.lyw.friend.mapper.user.UserSubmitMapper;
import com.lyw.friend.rabbit.JudgeProducer;
import com.lyw.friend.service.user.SubmissionRateLimiter;
import com.lyw.friend.service.user.impl.UserQuestionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SubmissionRateLimitBoundaryTest {
    private final UserQuestionServiceImpl service = new UserQuestionServiceImpl();
    private final SubmissionRateLimiter limiter = mock(SubmissionRateLimiter.class);
    private final QuestionMapper questions = mock(QuestionMapper.class);
    private final UserSubmitMapper submissions = mock(UserSubmitMapper.class);
    private final StateCacheService cache = mock(StateCacheService.class);
    private final JudgeProducer producer = mock(JudgeProducer.class);
    private final RemoteJudgeService judge = mock(RemoteJudgeService.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ThreadLocalUtil.set(Constants.USER_ID, 7L);
        ReflectionTestUtils.setField(service, "submissionRateLimiter", limiter);
        ReflectionTestUtils.setField(service, "questionMapper", questions);
        ReflectionTestUtils.setField(service, "userSubmitMapper", submissions);
        ReflectionTestUtils.setField(service, "stateCacheService", cache);
        ReflectionTestUtils.setField(service, "judgeProducer", producer);
        ReflectionTestUtils.setField(service, "remoteJudgeService", judge);
        UserQuestionController controller = new UserQuestionController();
        ReflectionTestUtils.setField(controller, "userQuestionService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() { ThreadLocalUtil.remove(); }

    @Test
    void bothEntrypointsRejectBeforeAnyAcceptanceSideEffect() {
        for (ResultCode code : List.of(ResultCode.FAILED_SUBMIT_FREQUENT,
                ResultCode.FAILED_SUBMIT_BUSY, ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE)) {
            doThrow(new ServiceException(code)).when(limiter).check(7L);
            assertEquals(code, assertThrows(ServiceException.class, () -> service.submit(request(9L))).getResultCode());
            assertEquals(code, assertThrows(ServiceException.class, () -> service.rabbitSubmit(request(10L))).getResultCode());
        }
        verify(limiter, times(6)).check(7L);
        verifyNoInteractions(questions, submissions, cache, producer, judge);
    }

    @Test
    void invalidParametersAndUnsupportedLanguageDoNotConsumeQuota() {
        for (boolean rabbit : List.of(false, true)) {
            assertCode(ResultCode.FAILED_PARAMS_VALIDATE, () -> submit(rabbit, null));
            UserQuestionSubmitDTO empty = request(9L);
            empty.setUserCode("  ");
            assertCode(ResultCode.FAILED_PARAMS_VALIDATE, () -> submit(rabbit, empty));
            UserQuestionSubmitDTO unsupported = request(9L);
            unsupported.setProgramType(999);
            assertCode(ResultCode.FAILED_NOT_SUPPORT_PROGRAM, () -> submit(rabbit, unsupported));
        }
        verifyNoInteractions(limiter, questions, submissions, cache, producer, judge);
    }

    @Test
    void missingIdentityDoesNotConsumeQuota() {
        ThreadLocalUtil.remove();
        assertCode(ResultCode.FAILED_UNAUTHORIZED, () -> service.submit(request(9L)));
        assertCode(ResultCode.FAILED_UNAUTHORIZED, () -> service.rabbitSubmit(request(9L)));
        verifyNoInteractions(limiter, questions, submissions, cache, producer, judge);
    }

    @Test
    void bothRoutesUseAuthenticatedIdentityAndCheckBeforeQuestionRead() {
        stubQuestion(9L);
        stubQuestion(10L);
        when(judge.doJudgeJavaCode(any())).thenReturn(Result.ok(new UserQuestionResultVO()));
        service.submit(request(9L));
        service.rabbitSubmit(request(10L));
        var order = inOrder(limiter, questions, cache, judge, producer);
        order.verify(limiter).check(7L);
        order.verify(questions).selectById(9L);
        order.verify(cache).update(anyString(), any(JudgeResultCache.class), anyLong(), any());
        order.verify(judge).doJudgeJavaCode(any());
        order.verify(limiter).check(7L);
        order.verify(questions).selectById(10L);
        order.verify(cache).update(anyString(), any(JudgeResultCache.class), anyLong(), any());
        order.verify(producer).produceMsg(any());
        ArgumentCaptor<JudgeSubmitDTO> dto = ArgumentCaptor.forClass(JudgeSubmitDTO.class);
        verify(producer).produceMsg(dto.capture());
        assertEquals(7L, dto.getValue().getUserId());
        assertNotNull(dto.getValue().getSubmitId());
    }

    @Test
    void laterSendFailurePreservesExistingReceiptAndErrorContract() {
        stubQuestion(9L);
        doThrow(new IllegalStateException("测试消息发送失败")).when(producer).produceMsg(any());
        var response = service.rabbitSubmit(request(9L));
        assertEquals(ResultCode.FAILED_RABBIT_PRODUCE.getCode(), response.getCode());
        assertNotNull(response.getData().getSubmitId());
        verify(limiter).check(7L);
        verifyNoMoreInteractions(limiter);
        verify(cache, times(2)).update(anyString(), any(JudgeResultCache.class), anyLong(), any());
    }

    @Test
    void allNewFailuresHaveRealHttpStatusAndTheExistingResultShapeOnBothRoutes() throws Exception {
        for (String path : List.of("/user/question/submit", "/user/question/rabbit/submit")) {
            for (ResultCode code : List.of(ResultCode.FAILED_SUBMIT_FREQUENT,
                    ResultCode.FAILED_SUBMIT_BUSY, ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE)) {
                doThrow(new ServiceException(code)).when(limiter).check(7L);
                MvcResult result = perform(path, request(9L));
                assertEquals(code == ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE ? 503 : 429,
                        result.getResponse().getStatus());
                JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
                assertEquals(code.getCode(), body.path("code").asInt());
                assertEquals(code.getMsg(), body.path("msg").asText());
                assertTrue(body.has("data"));
                assertTrue(body.get("data").isNull());
                assertEquals(3, body.size());
            }
        }
        verifyNoInteractions(questions, submissions, cache, producer, judge);
    }

    @Test
    void existingValidationErrorsKeepTheirHttpAndBusinessStatus() throws Exception {
        UserQuestionSubmitDTO invalid = request(0L);
        MvcResult result = perform("/user/question/rabbit/submit", invalid);
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(ResultCode.FAILED_PARAMS_VALIDATE.getCode(), body.path("code").asInt());
        verifyNoInteractions(limiter, questions, submissions, cache, producer, judge);
    }

    @Test
    void rateLimitAdviceDoesNotEmitAnExceptionStackTrace() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            doThrow(new ServiceException(ResultCode.FAILED_SUBMIT_FREQUENT)).when(limiter).check(7L);
            assertEquals(429, perform("/user/question/submit", request(9L)).getResponse().getStatus());
            assertTrue(appender.list.stream().noneMatch(event -> event.getThrowableProxy() != null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private MvcResult perform(String path, UserQuestionSubmitDTO request) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andReturn();
    }

    private void submit(boolean rabbit, UserQuestionSubmitDTO request) {
        if (rabbit) service.rabbitSubmit(request); else service.submit(request);
    }

    private void assertCode(ResultCode expected, Runnable action) {
        assertEquals(expected, assertThrows(ServiceException.class, action::run).getResultCode());
    }

    private UserQuestionSubmitDTO request(Long questionId) {
        UserQuestionSubmitDTO request = new UserQuestionSubmitDTO();
        request.setQuestionId(questionId);
        request.setProgramType(0);
        request.setUserCode("public class Solution {}");
        return request;
    }

    private void stubQuestion(Long id) {
        Question question = new Question();
        question.setQuestionId(id);
        question.setMainFuc("");
        question.setQuestionCase("[{\"input\":\"1 2\",\"output\":\"3\"}]");
        when(questions.selectById(id)).thenReturn(question);
    }
}

