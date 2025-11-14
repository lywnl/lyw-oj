package com.lyw.system.service.exam.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.exam.Exam;
import com.lyw.system.domain.exam.ExamQuestion;
import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamEditDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.dto.ExamQuestionAddDTO;
import com.lyw.system.domain.exam.vo.ExamDetailVO;
import com.lyw.system.domain.exam.vo.ExamVO;
import com.lyw.system.domain.question.Question;
import com.lyw.system.domain.question.vo.QuestionVO;
import com.lyw.system.manager.ExamCacheManager;
import com.lyw.system.mapper.exam.ExamMapper;
import com.lyw.system.mapper.exam.ExamQuestionMapper;
import com.lyw.system.mapper.question.QuestionMapper;
import com.lyw.system.service.exam.IExamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@Slf4j
public class ExamServiceImpl extends ServiceImpl<ExamQuestionMapper, ExamQuestion> implements IExamService {

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private ExamQuestionMapper examQuestionMapper;

    @Autowired
    private ExamCacheManager examCacheManager;

    @Override
    public List<ExamVO> list(ExamQueryDTO examQueryDTO) {
        PageHelper.startPage(examQueryDTO.getPageNum(), examQueryDTO.getPageSize());
        return examMapper.selectExamList(examQueryDTO);
    }

    @Override
    public String add(ExamAddDTO examAddDTO) {
        checkExamSaveParams(examAddDTO, null);

        Exam exam = new Exam();
        BeanUtils.copyProperties(examAddDTO, exam);
        examMapper.insert(exam);

        return exam.getExamId().toString();
    }


    @Override
    public boolean addQuestion(ExamQuestionAddDTO examQuestionDTO) {
        Exam exam = getExam(examQuestionDTO.getExamId());

        checkExam(exam);

        LinkedHashSet<Long> questionIds = examQuestionDTO.getQuestionIdSet();
        if (CollectionUtil.isEmpty(questionIds)) {
            log.info("没有添加任何题目");
            return true;
        }

        List<Question> questions = questionMapper.selectBatchIds(examQuestionDTO.getQuestionIdSet());

        if (CollectionUtil.isEmpty(questions) || questions.size() != examQuestionDTO.getQuestionIdSet().size()) {
            throw new ServiceException(ResultCode.EXAM_QUESTION_NOT_EXISTS);
        }

        return saveExamQuestion(examQuestionDTO);
    }



    @Override
    public int deleteQuestion(Long examId, Long questionId) {
        Exam exam = getExam(examId);

        checkExam(exam);

        return examQuestionMapper.delete(new LambdaQueryWrapper<ExamQuestion>()
                .eq(ExamQuestion::getExamId, examId)
                .eq(ExamQuestion::getQuestionId, questionId));
    }

    @Override
    public int delete(Long examId) {
        Exam exam = getExam(examId);
        if (Constants.TRUE.equals(exam.getStatus())) {
            throw new ServiceException(ResultCode.EXAM_IS_PUBLISH);
        }

        checkExam(exam);
        examQuestionMapper.delete(new LambdaQueryWrapper<ExamQuestion>()
                .eq(ExamQuestion::getExamId, examId));
        return examMapper.deleteById(exam);
    }

    @Override
    public int publish(Long examId) {
        Exam exam = getExam(examId);

        if (exam.getEndTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(ResultCode.EXAM_IS_FINISH);
        }

        Long count = examQuestionMapper.selectCount(new LambdaQueryWrapper<ExamQuestion>()
                .eq(ExamQuestion::getExamId, examId));

        if (count == null || count <= 0) {
            throw new ServiceException(ResultCode.EXAM_NOT_HAS_QUESTION);
        }

        exam.setStatus(Constants.TRUE);

        //将新发布的竞赛存储到redis中 key e:t:l(未结束的竞赛) e:h:l (已结束的竞赛) e:d:examId(竞赛的具体信息)
        examCacheManager.addCache(exam);

        return examMapper.updateById(exam);
    }

    @Override
    public int cancelPublish(Long examId) {
        Exam exam = getExam(examId);
        checkExam(exam);

        if (exam.getEndTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(ResultCode.EXAM_IS_FINISH);
        }

        exam.setStatus(Constants.FALSE);

        //撤销发布 缓存中删除该竞赛的数据
        examCacheManager.deleteCache(examId);

        return examMapper.updateById(exam);
    }

    @Override
    public ExamDetailVO detail(Long examId) {
        ExamDetailVO examDetailVO = new ExamDetailVO();
        Exam exam = getExam(examId);

        BeanUtils.copyProperties(exam, examDetailVO);
        List<ExamQuestion> examQuestions = examQuestionMapper.selectList(new LambdaQueryWrapper<ExamQuestion>()
                .select(ExamQuestion::getQuestionId)
                .eq(ExamQuestion::getExamId, examId)
                .orderByAsc(ExamQuestion::getQuestionOrder));

        if (CollectionUtil.isEmpty(examQuestions)) {
            //返回详情 只包含竞赛的基本信息
            return examDetailVO;
        }

        List<Long> questionIdList = examQuestions.stream().map(ExamQuestion::getQuestionId).toList();

        List<Question> questionList = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .select(Question::getQuestionId, Question::getTitle, Question::getDifficulty)
                .in(Question::getQuestionId, questionIdList));

        List<QuestionVO> questionVOList = BeanUtil.copyToList(questionList, QuestionVO.class);

        examDetailVO.setExamQuestionList(questionVOList);
        return examDetailVO;
    }

    @Override
    public int edit(ExamEditDTO examEditDTO) {
        //参数判断
        Exam exam = getExam(examEditDTO.getExamId());
        checkExamSaveParams(examEditDTO, examEditDTO.getExamId());

        exam.setTitle(examEditDTO.getTitle());
        exam.setStartTime(examEditDTO.getStartTime());
        exam.setEndTime(examEditDTO.getEndTime());

        return examMapper.updateById(exam);
    }


    /**
     * 检查添加竞赛的参数
     */
    private void checkExamSaveParams(ExamAddDTO examSaveDTO, Long examId) {
        List<Exam> exams = examMapper.selectList(new LambdaQueryWrapper<Exam>()
                .eq(Exam::getTitle, examSaveDTO.getTitle())
                .ne(examId != null, Exam::getExamId, examId));

        //判断竞赛是否已经存在
        if (CollectionUtil.isNotEmpty(exams)) {
            throw new ServiceException(ResultCode.FAILED_ALREADY_EXISTS);
        }

        //判断开始时间不能早于当前时间
        if (examSaveDTO.getStartTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(ResultCode.EXAM_START_TIME_BEFORE_CURRENT_TIME);
        }

        //判断开始时间不能晚于结束时间
        if (examSaveDTO.getStartTime().isAfter(examSaveDTO.getEndTime())) {
            throw new ServiceException(ResultCode.EXAM_START_TIME_AFTER_END_TIME);
        }
    }

    private boolean saveExamQuestion(ExamQuestionAddDTO examQuestionDTO) {
        int num = 1;
        List<ExamQuestion> examQuestions = new ArrayList<>();

        for (Long questionId : examQuestionDTO.getQuestionIdSet()) {
            ExamQuestion examQuestion = new ExamQuestion();
            examQuestion.setExamId(examQuestionDTO.getExamId());
            examQuestion.setQuestionId(questionId);
            examQuestion.setQuestionOrder(num++);
            examQuestions.add(examQuestion);
        }

        return saveBatch(examQuestions);
    }

    private void checkExam(Exam exam) {
        if (exam.getStartTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(ResultCode.EXAM_STARTED);
        }
    }

    private Exam getExam(Long examId) {
        Exam exam = examMapper.selectById(examId);

        if (exam == null) {
            throw new ServiceException(ResultCode.EXAM_NOT_EXISTS);
        }

        return exam;
    }
}
