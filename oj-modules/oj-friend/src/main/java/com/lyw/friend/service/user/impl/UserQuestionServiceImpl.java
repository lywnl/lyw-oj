package com.lyw.friend.service.user.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lyw.api.domain.UserExeResult;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.api.domain.vo.UserQuestionSubmitVO;
import com.lyw.api.service.RemoteJudgeService;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ProgramType;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.domain.question.Question;
import com.lyw.friend.domain.question.QuestionCase;
import com.lyw.friend.domain.user.UserSubmit;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.mapper.question.QuestionMapper;
import com.lyw.friend.mapper.user.UserSubmitMapper;
import com.lyw.friend.rabbit.JudgeProducer;
import com.lyw.friend.service.user.IUserQuestionService;
import com.lyw.friend.service.user.SubmissionRateLimiter;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UserQuestionServiceImpl implements IUserQuestionService {

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private UserSubmitMapper userSubmitMapper;

    @Autowired
    private RemoteJudgeService remoteJudgeService;

    @Autowired
    private JudgeProducer judgeProducer;

    @Autowired
    private StateCacheService stateCacheService;

    @Autowired
    private SubmissionRateLimiter submissionRateLimiter;


    @Override
    public Result<UserQuestionResultVO> submit(UserQuestionSubmitDTO userQuestionSubmitDTO) {
        JudgeSubmitDTO submission = prepareSubmission(userQuestionSubmitDTO);
        try {
            return remoteJudgeService.doJudgeJavaCode(submission);
        } catch (Exception e) {
            String message = "判题服务暂时不可用，请重新查询结果";
            cacheBestEffort(JudgeResultCache.error(submission.getUserId(), submission.getSubmitId(), message));
            return Result.fail(ResultCode.ERROR, UserQuestionResultVO.systemError(submission.getSubmitId(), message));
        }
    }

    @Override
    public Result<UserQuestionSubmitVO> rabbitSubmit(UserQuestionSubmitDTO userQuestionSubmitDTO) {
        JudgeSubmitDTO submission = prepareSubmission(userQuestionSubmitDTO);
        UserQuestionSubmitVO receipt = new UserQuestionSubmitVO(submission.getSubmitId());
        try {
            judgeProducer.produceMsg(submission);
            return Result.ok(receipt);
        } catch (Exception e) {
            cacheBestEffort(JudgeResultCache.error(submission.getUserId(), submission.getSubmitId(),
                    "判题消息发送异常，请继续查询结果"));
            return Result.fail(ResultCode.FAILED_RABBIT_PRODUCE, receipt);
        }
    }

    @Override
    public UserQuestionResultVO getExeResult(Long submitId, boolean checkDatabase) {
        if (submitId == null || submitId <= 0) throw new ServiceException(ResultCode.FAILED_PARAMS_VALIDATE);
        Long userId = currentUserId();
        UserQuestionResultVO cached = null;
        boolean cacheReadable = true;
        try {
            cached = readCachedResult(userId, submitId);
        } catch (Exception e) {
            cacheReadable = false;
            log.warn("结果缓存读取失败，submitId={}，原因={}", submitId, e.getMessage());
        }
        if (cached != null && cached.getResultState() == JudgeResultState.JUDGED) return cached;
        if (!checkDatabase && cacheReadable) return cached == null ? UserQuestionResultVO.noResult(submitId) : cached;

        UserSubmit saved = userSubmitMapper.selectUserSubmit(userId, submitId);
        if (saved != null) {
            if (!Objects.equals(saved.getSubmitId(), submitId)
                    || !(Objects.equals(saved.getPass(), 0) || Objects.equals(saved.getPass(), 1))) {
                throw new ServiceException(ResultCode.ERROR);
            }
            UserQuestionResultVO result = new UserQuestionResultVO();
            result.setSubmitId(saved.getSubmitId());
            result.setResultState(JudgeResultState.JUDGED);
            result.setPass(saved.getPass());
            result.setExeMessage(saved.getExeMessage());
            result.setScore(saved.getScore());
            if (StrUtil.isNotEmpty(saved.getCaseJudgeRes())) {
                result.setUserExeResultList(JSON.parseArray(saved.getCaseJudgeRes(), UserExeResult.class));
            }
            cacheBestEffort(JudgeResultCache.judged(userId, result));
            return result;
        }
        // 核对期间可能出现新结果，数据库无记录后再确认一次缓存。
        try {
            cached = readCachedResult(userId, submitId);
            return cached == null ? UserQuestionResultVO.noResult(submitId) : cached;
        } catch (Exception e) {
            throw new ServiceException(ResultCode.ERROR);
        }
    }

    private UserQuestionResultVO readCachedResult(Long userId, Long submitId) {
        JudgeResultCache cache = stateCacheService.get(CacheConstants.getJudgeResultKey(userId, submitId), JudgeResultCache.class);
        return cache == null ? null : cache.checkedResult(userId, submitId);
    }

    private Long currentUserId() {
        Long userId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        if (userId == null || userId <= 0) throw new ServiceException(ResultCode.FAILED_UNAUTHORIZED);
        return userId;
    }

    private JudgeSubmitDTO prepareSubmission(UserQuestionSubmitDTO request) {
        if (request == null || request.getQuestionId() == null || request.getQuestionId() <= 0
                || request.getUserCode() == null || request.getUserCode().isBlank()
                || (request.getExamId() != null && request.getExamId() <= 0)) {
            throw new ServiceException(ResultCode.FAILED_PARAMS_VALIDATE);
        }
        if (!ProgramType.JAVA.getValue().equals(request.getProgramType())) {
            throw new ServiceException(ResultCode.FAILED_NOT_SUPPORT_PROGRAM);
        }
        Long userId = currentUserId();
        submissionRateLimiter.check(userId);
        JudgeSubmitDTO submission = assembleJudgeSubmitDTO(request);
        submission.setSubmitId(IdWorker.getId());
        cacheBestEffort(JudgeResultCache.waiting(submission.getUserId(), submission.getSubmitId()));
        return submission;
    }

    private void cacheBestEffort(JudgeResultCache cache) {
        try {
            stateCacheService.update(CacheConstants.getJudgeResultKey(cache.getUserId(), cache.getSubmitId()),
                    cache, CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("结果缓存更新失败，submitId={}，原因={}", cache.getSubmitId(), e.getMessage());
        }
    }


    private JudgeSubmitDTO assembleJudgeSubmitDTO(UserQuestionSubmitDTO submitDTO) {
        Long questionId = submitDTO.getQuestionId();
        Question question = questionMapper.selectById(questionId);
        if (question == null) throw new ServiceException(ResultCode.FAILED_NOT_EXISTS);
        JudgeSubmitDTO judgeSubmitDTO = new JudgeSubmitDTO();
        BeanUtils.copyProperties(question, judgeSubmitDTO);

        judgeSubmitDTO.setUserId(ThreadLocalUtil.get(Constants.USER_ID, Long.class));
        judgeSubmitDTO.setQuestionId(questionId);
        judgeSubmitDTO.setExamId(submitDTO.getExamId());
        judgeSubmitDTO.setProgramType(submitDTO.getProgramType());
        judgeSubmitDTO.setUserCode(codeConnect(submitDTO.getUserCode(), question.getMainFuc()));

        List<QuestionCase> questionCaseList = JSONUtil.toList(question.getQuestionCase(), QuestionCase.class);
        List<String> inputList = questionCaseList.stream().map(questionCase -> questionCase.getInput()).toList();
        judgeSubmitDTO.setInputList(inputList);
        List<String> outputList = questionCaseList.stream().map(questionCase -> questionCase.getOutput()).toList();
        judgeSubmitDTO.setOutputList(outputList);

        return judgeSubmitDTO;
    }

    private String codeConnect(String userCode, String mainFunc) {
            String targetCharacter = "}";
            int targetLastIndex = userCode.lastIndexOf(targetCharacter);
            if (targetLastIndex != -1) {
            return userCode.substring(0, targetLastIndex) + "\n" + mainFunc + "\n" + userCode.substring(targetLastIndex);
        }
        throw new ServiceException(ResultCode.FAILED);
    }
}
