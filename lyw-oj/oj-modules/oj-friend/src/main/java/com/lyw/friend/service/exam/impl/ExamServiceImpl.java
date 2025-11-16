package com.lyw.friend.service.exam.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.domain.exam.vo.ExamVO;
import com.lyw.friend.manager.ExamCacheManager;
import com.lyw.friend.mapper.exam.ExamMapper;
import com.lyw.friend.service.exam.IExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExamServiceImpl implements IExamService {

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private ExamCacheManager examCacheManager;

    @Override
    public List<ExamVO> list(ExamQueryDTO examQueryDTO) {
        PageHelper.startPage(examQueryDTO.getPageNum(), examQueryDTO.getPageSize());
        return examMapper.selectExamList(examQueryDTO);
    }

    @Override
    public TableDataInfo redisList(ExamQueryDTO examQueryDTO) {
        //从redis中获取 竞赛列表的数据
        Long total = examCacheManager.getListSize(examQueryDTO.getType(), null);
        List<ExamVO> examVOList;

        if (total == null || total == 0) {
            examVOList = list(examQueryDTO);
            examCacheManager.refreshCache(examQueryDTO.getType(), null);
            total = new PageInfo<>(examVOList).getTotal();
        } else {
            examVOList = examCacheManager.getExamVOList(examQueryDTO, null);
            total = examCacheManager.getListSize(examQueryDTO.getType(), null);
        }

        if (CollectionUtil.isEmpty(examVOList)) {
            return TableDataInfo.empty();
        }

        assembleExamVOList(examVOList);
        return TableDataInfo.success(examVOList, total);
    }

    @Override
    public String getFirstQuestion(Long examId) {
        checkAndRefresh(examId);

        return examCacheManager.getFirstQuestion(examId).toString();
    }


    @Override
    public String preQuestion(Long examId, Long questionId) {
        checkAndRefresh(examId);

        return examCacheManager.preQuestion(examId, questionId).toString();
    }

    @Override
    public String nextQuestion(Long examId, Long questionId) {
        checkAndRefresh(examId);

        return examCacheManager.nextQuestion(examId, questionId).toString();
    }

    private void assembleExamVOList(List<ExamVO> examVOList) {
        Long userId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        List<Long> userExamIdList = examCacheManager.getAllUserExamList(userId);
        if (CollectionUtil.isEmpty(userExamIdList)) {
            return;
        }
        for (ExamVO examVO : examVOList) {
            if (userExamIdList.contains(examVO.getExamId())) {
                examVO.setEnter(true);
            }
        }
    }

    private void checkAndRefresh(Long examId) {
        Long examQuestionListSize = examCacheManager.getExamQuestionListSize(examId);
        if (examQuestionListSize == null || examQuestionListSize <= 0) {
            examCacheManager.refreshExamQuestionCache(examId);
        }
    }

}
