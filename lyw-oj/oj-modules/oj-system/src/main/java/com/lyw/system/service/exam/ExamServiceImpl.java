package com.lyw.system.service.exam;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.exam.Exam;
import com.lyw.system.domain.exam.ExamQuestion;
import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.dto.ExamQuestionDTO;
import com.lyw.system.domain.exam.vo.ExamDetailVO;
import com.lyw.system.domain.exam.vo.ExamVO;
import com.lyw.system.domain.question.Question;
import com.lyw.system.domain.question.vo.QuestionVO;
import com.lyw.system.mapper.exam.ExamMapper;
import com.lyw.system.mapper.exam.ExamQuestionMapper;
import com.lyw.system.mapper.question.QuestionMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ExamServiceImpl extends ServiceImpl<ExamQuestionMapper, ExamQuestion> implements IExamService {

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private ExamQuestionMapper examQuestionMapper;

    @Override
    public List<ExamVO> list(ExamQueryDTO examQueryDTO) {
        PageHelper.startPage(examQueryDTO.getPageNum(), examQueryDTO.getPageSize());
        return examMapper.selectExamList(examQueryDTO);
    }

    @Override
    public String add(ExamAddDTO examAddDTO) {
        List<Exam> exams = examMapper.selectList(new LambdaQueryWrapper<Exam>().eq(Exam::getTitle, examAddDTO.getTitle()));

        if (CollectionUtil.isNotEmpty(exams)) {
            throw new ServiceException(ResultCode.FAILED_ALREADY_EXISTS);
        }

        if (examAddDTO.getStartTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(ResultCode.EXAM_START_TIME_BEFORE_CURRENT_TIME);
        }

        if (examAddDTO.getStartTime().isAfter(examAddDTO.getEndTime())) {
            throw new ServiceException(ResultCode.EXAM_START_TIME_AFTER_END_TIME);
        }

        Exam exam = new Exam();
        BeanUtils.copyProperties(examAddDTO, exam);
        examMapper.insert(exam);

        return exam.getExamId().toString();
    }

    @Override
    public boolean addQuestion(ExamQuestionDTO examQuestionDTO) {
        getExam(examQuestionDTO.getExamId());

        LinkedHashSet<Long> questionIds = examQuestionDTO.getQuestionIds();
        if (CollectionUtil.isEmpty(questionIds)) {
            return true;
        }

        List<Question> questions = questionMapper.selectBatchIds(examQuestionDTO.getQuestionIds());

        if (CollectionUtil.isEmpty(questions) || questions.size() != examQuestionDTO.getQuestionIds().size()) {
            throw new ServiceException(ResultCode.EXAM_QUESTION_NOT_EXISTS);
        }

        return saveExamQuestion(examQuestionDTO);
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

    private boolean saveExamQuestion(ExamQuestionDTO examQuestionDTO) {
        int num = 1;
        List<ExamQuestion> examQuestions = new ArrayList<>();

        for (Long questionId : examQuestionDTO.getQuestionIds()) {
            ExamQuestion examQuestion = new ExamQuestion();
            examQuestion.setExamId(examQuestionDTO.getExamId());
            examQuestion.setQuestionId(questionId);
            examQuestion.setQuestionOrder(num++);
            examQuestions.add(examQuestion);
        }

        return saveBatch(examQuestions);
    }

    private Exam getExam(Long examId) {
        Exam exam = examMapper.selectById(examId);

        if (exam == null) {
            throw new ServiceException(ResultCode.EXAM_NOT_EXISTS);
        }

        return exam;
    }
}
