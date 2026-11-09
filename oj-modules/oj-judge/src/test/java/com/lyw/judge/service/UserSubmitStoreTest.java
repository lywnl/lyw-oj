package com.lyw.judge.service;

import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.judge.domain.UserSubmit;
import com.lyw.judge.mapper.UserSubmitMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserSubmitStoreTest {
    @Test
    void rollbackFinishesBeforeReusingPrimaryKeyWinner() {
        UserSubmitMapper mapper = mock(UserSubmitMapper.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(mapper.insert(any(UserSubmit.class))).thenThrow(new DuplicateKeyException("重复主键"));
        var submission = JudgeServiceIdempotencyTest.submission();
        UserSubmit winner = new UserSubmit();
        winner.setSubmitId(submission.getSubmitId());
        winner.setUserId(submission.getUserId());
        winner.setQuestionId(submission.getQuestionId());
        winner.setProgramType(submission.getProgramType());
        winner.setUserCode(submission.getUserCode());
        winner.setPass(0);
        winner.setScore(0);
        winner.setExeMessage("先保存的真实结果");
        when(mapper.selectById(submission.getSubmitId())).thenReturn(winner);
        UserQuestionResultVO current = UserQuestionResultVO.noResult(submission.getSubmitId());
        current.setResultState(JudgeResultState.JUDGED);
        current.setPass(1);
        assertSame(winner, new UserSubmitStore(mapper, manager).saveOrReuse(submission, current));
        InOrder order = inOrder(manager, mapper);
        order.verify(manager).getTransaction(any());
        order.verify(mapper).insert(any(UserSubmit.class));
        order.verify(manager).rollback(transaction);
        order.verify(mapper).selectById(submission.getSubmitId());
    }

    @Test
    void systemErrorCannotEnterInsertTransaction() {
        UserSubmitMapper mapper = mock(UserSubmitMapper.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        assertThrows(IllegalArgumentException.class, () -> new UserSubmitStore(mapper, manager)
                .saveOrReuse(JudgeServiceIdempotencyTest.submission(), UserQuestionResultVO.systemError(10001L, "系统错误")));
        verifyNoInteractions(mapper, manager);
    }
}
