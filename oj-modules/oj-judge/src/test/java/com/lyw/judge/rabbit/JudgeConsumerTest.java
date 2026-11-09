package com.lyw.judge.rabbit;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.service.IJudgeService;
import com.lyw.judge.service.JudgeSystemException;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JudgeConsumerTest {
    @Test
    void acknowledgementFailureDoesNotRepeatBusiness() throws Exception {
        IJudgeService service = mock(IJudgeService.class);
        Channel channel = mock(Channel.class);
        UserQuestionResultVO result = UserQuestionResultVO.noResult(10001L);
        result.setResultState(JudgeResultState.JUDGED);
        result.setPass(1);
        when(service.doJudgeJavaCode(any())).thenReturn(result);
        doThrow(new IOException("连接中断")).when(channel).basicAck(1L, false);
        consumer(service).consumeMsg(submission(), channel, 1L);
        verify(service).doJudgeJavaCode(any());
        verify(channel).abort();
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void busyIsRecognizedByStatusAndImmediatelyDeadLettered() throws Exception {
        IJudgeService service = mock(IJudgeService.class);
        when(service.doJudgeJavaCode(any())).thenThrow(new JudgeSystemException(CodeRunStatus.SYSTEM_BUSY));
        Channel channel = mock(Channel.class);
        consumer(service).consumeMsg(submission(), channel, 2L);
        verify(service).doJudgeJavaCode(any());
        verify(channel).basicNack(2L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void failedBusinessHasThreeAttemptsThenDeadLetter() throws Exception {
        IJudgeService service = mock(IJudgeService.class);
        when(service.doJudgeJavaCode(any())).thenThrow(new IllegalStateException("数据库暂不可用"));
        Channel channel = mock(Channel.class);
        consumer(service).consumeMsg(submission(), channel, 3L);
        verify(service, times(3)).doJudgeJavaCode(any());
        verify(channel).basicNack(3L, false, false);
    }

    @Test
    void missingIdDoesNotExecuteBusiness() throws Exception {
        IJudgeService service = mock(IJudgeService.class);
        Channel channel = mock(Channel.class);
        consumer(service).consumeMsg(new JudgeSubmitDTO(), channel, 4L);
        verifyNoInteractions(service);
        verify(channel).basicNack(4L, false, false);
    }

    private JudgeConsumer consumer(IJudgeService service) {
        JudgeConsumer consumer = new JudgeConsumer();
        ReflectionTestUtils.setField(consumer, "judgeService", service);
        return consumer;
    }

    static JudgeSubmitDTO submission() {
        JudgeSubmitDTO submission = new JudgeSubmitDTO();
        submission.setSubmitId(10001L);
        submission.setUserId(7L);
        submission.setQuestionId(9L);
        submission.setProgramType(0);
        return submission;
    }
}
