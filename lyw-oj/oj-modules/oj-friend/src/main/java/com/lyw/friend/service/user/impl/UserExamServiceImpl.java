package com.lyw.friend.service.user.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.common.core.enums.ExamListType;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.common.security.service.TokenService;
import com.lyw.friend.domain.exam.Exam;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.domain.exam.vo.ExamVO;
import com.lyw.friend.domain.user.UserExam;
import com.lyw.friend.domain.user.vo.UserVO;
import com.lyw.friend.manager.ExamCacheManager;
import com.lyw.friend.manager.UserCacheManager;
import com.lyw.friend.mapper.exam.ExamMapper;
import com.lyw.friend.mapper.user.UserExamMapper;
import com.lyw.friend.service.user.IUserExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserExamServiceImpl implements IUserExamService {

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private UserExamMapper userExamMapper;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ExamCacheManager examCacheManager;

    @Value("${jwt.secret}")
    private String secret;

    @Override
    public int enter(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new ServiceException(ResultCode.EXAM_NOT_EXISTS);
        }

        if (exam.getStartTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(ResultCode.EXAM_STARTED);
        }

        Long userId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        UserExam userExam = userExamMapper.selectOne(new LambdaQueryWrapper<UserExam>()
                .eq(UserExam::getUserId, userId)
                .eq(UserExam::getExamId, examId));
        if (userExam != null) {
            throw new ServiceException(ResultCode.USER_EXAM_HAS_ENTER);
        }

        examCacheManager.addUserExamCache(userId, examId);
        userExam = new UserExam();
        userExam.setUserId(userId);
        userExam.setExamId(examId);

        return userExamMapper.insert(userExam);
    }

    @Override
    public TableDataInfo list(ExamQueryDTO examQueryDTO) {
        Long userId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        Long total = examCacheManager.getListSize(ExamListType.USER_EXAM_LIST.getValue(), userId);

        List<ExamVO> examVOList;
        if (total == null || total == 0) {
            //从数据中获取竞赛列表
            PageHelper.startPage(examQueryDTO.getPageNum(), examQueryDTO.getPageSize());
            examVOList = userExamMapper.selectUserExamList(userId);
            examCacheManager.refreshCache(examQueryDTO.getType(), userId);
            total = new PageInfo<>(examVOList).getTotal();
        } else {
            examVOList = examCacheManager.getExamVOList(examQueryDTO, userId);
            total = examCacheManager.getListSize(examQueryDTO.getType(), userId);
        }

        if (CollectionUtil.isEmpty(examVOList)) {
            return TableDataInfo.empty();
        }

        return TableDataInfo.success(examVOList, total);
    }
}
