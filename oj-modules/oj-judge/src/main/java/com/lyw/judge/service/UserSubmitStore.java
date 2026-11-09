package com.lyw.judge.service;

import com.alibaba.fastjson2.JSON;
import com.lyw.api.domain.UserExeResult;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.judge.domain.UserSubmit;
import com.lyw.judge.mapper.UserSubmitMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class UserSubmitStore {
    private final UserSubmitMapper mapper;
    private final TransactionTemplate insertTransaction;

    public UserSubmitStore(UserSubmitMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.insertTransaction = new TransactionTemplate(manager);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UserSubmit findCompleted(JudgeSubmitDTO submission) {
        UserSubmit saved = mapper.selectById(submission.getSubmitId());
        if (saved != null) verifySameSubmission(saved, submission);
        return saved;
    }

    public UserSubmit saveOrReuse(JudgeSubmitDTO submission, UserQuestionResultVO result) {
        if (result.getResultState() != JudgeResultState.JUDGED
                || !(Objects.equals(result.getPass(), 0) || Objects.equals(result.getPass(), 1))) {
            throw new IllegalArgumentException("系统状态不能写入提交记录");
        }
        UserSubmit userSubmit = new UserSubmit();
        BeanUtils.copyProperties(result, userSubmit);
        userSubmit.setSubmitId(submission.getSubmitId());
        userSubmit.setUserId(submission.getUserId());
        userSubmit.setQuestionId(submission.getQuestionId());
        userSubmit.setExamId(submission.getExamId());
        userSubmit.setProgramType(submission.getProgramType());
        userSubmit.setUserCode(submission.getUserCode());
        userSubmit.setCaseJudgeRes(JSON.toJSONString(result.getUserExeResultList()));
        userSubmit.setCreateBy(submission.getUserId());
        userSubmit.setCreateTime(LocalDateTime.now());
        try {
            insertTransaction.executeWithoutResult(status -> mapper.insert(userSubmit));
            return userSubmit;
        } catch (DuplicateKeyException conflict) {
            // executeWithoutResult 已结束失败事务，再读取获胜记录，避免在回滚事务内复用。
            UserSubmit saved = findCompleted(submission);
            if (saved == null) throw new IllegalStateException("提交主键冲突但未找到真实结果", conflict);
            return saved;
        }
    }

    private void verifySameSubmission(UserSubmit saved, JudgeSubmitDTO incoming) {
        if (!Objects.equals(saved.getSubmitId(), incoming.getSubmitId())
                || !Objects.equals(saved.getUserId(), incoming.getUserId())
                || !Objects.equals(saved.getQuestionId(), incoming.getQuestionId())
                || !Objects.equals(saved.getExamId(), incoming.getExamId())
                || !Objects.equals(saved.getProgramType(), incoming.getProgramType())
                || !Objects.equals(saved.getUserCode(), incoming.getUserCode())
                || !(Objects.equals(saved.getPass(), 0) || Objects.equals(saved.getPass(), 1))
                || saved.getExeMessage() == null || saved.getScore() == null) {
            throw new IllegalStateException("提交身份或已保存结果不匹配");
        }
    }

    public static UserQuestionResultVO toResult(UserSubmit saved) {
        UserQuestionResultVO result = new UserQuestionResultVO();
        result.setSubmitId(saved.getSubmitId());
        result.setResultState(JudgeResultState.JUDGED);
        result.setPass(saved.getPass());
        result.setExeMessage(saved.getExeMessage());
        result.setScore(saved.getScore());
        result.setUserExeResultList(saved.getCaseJudgeRes() == null || saved.getCaseJudgeRes().isBlank()
                ? List.of() : JSON.parseArray(saved.getCaseJudgeRes(), UserExeResult.class));
        return result;
    }
}
