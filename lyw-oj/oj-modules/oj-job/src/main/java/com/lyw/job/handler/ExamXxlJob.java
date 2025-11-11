package com.lyw.job.handler;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.redis.service.RedisService;
import com.lyw.job.domain.exam.Exam;
import com.lyw.job.mapper.ExamMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
@Slf4j
public class ExamXxlJob {

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private RedisService redisService;

    @XxlJob("examListOrganizeHandler")
    public void examListOrganizeHandler() {
        //  统计哪些竞赛应该存入未完赛的列表中  哪些竞赛应该存入历史竞赛列表中   统计出来了之后，再存入对应的缓存中
        log.info("*** examListOrganizeHandler ***");
        List<Exam> unFinishList = examMapper.selectList(new LambdaQueryWrapper<Exam>()
                .select(Exam::getExamId, Exam::getTitle, Exam::getStartTime, Exam::getEndTime)
                .gt(Exam::getEndTime, LocalDateTime.now())
                .eq(Exam::getStatus, Constants.TRUE)
                .orderByDesc(Exam::getCreateTime));
        refreshCache(unFinishList, CacheConstants.EXAM_UNFINISHED_LIST);

        List<Exam> historyList = examMapper.selectList(new LambdaQueryWrapper<Exam>()
                .select(Exam::getExamId, Exam::getTitle, Exam::getStartTime, Exam::getEndTime)
                .le(Exam::getEndTime, LocalDateTime.now())
                .eq(Exam::getStatus, Constants.TRUE)
                .orderByDesc(Exam::getCreateTime));

        refreshCache(historyList, CacheConstants.EXAM_HISTORY_LIST);
        log.info("*** examListOrganizeHandler 统计结束 ***");
    }


    public void refreshCache(List<Exam> examList, String examListKey) {
        if (CollectionUtil.isEmpty(examList)) {
            return;
        }

        Map<String, Exam> examMap = new HashMap<>();
        List<Long> examIdList = new ArrayList<>();
        for (Exam exam : examList) {
            examMap.put(getDetailKey(exam.getExamId()), exam);
            examIdList.add(exam.getExamId());
        }
        redisService.multiSet(examMap);  //刷新详情缓存
        redisService.deleteObject(examListKey);
        redisService.rightPushAll(examListKey, examIdList);      //刷新列表缓存
    }

    private String getDetailKey(Long examId) {
        return CacheConstants.EXAM_DETAIL + examId;
    }

//    private String getUserMsgListKey(Long userId) {
//        return CacheConstants.USER_MESSAGE_LIST + userId;
//    }

//    private String getMsgDetailKey(Long textId) {
//        return CacheConstants.MESSAGE_DETAIL + textId;
//    }

//    private String getExamRankListKey(Long examId) {
//        return CacheConstants.EXAM_RANK_LIST + examId;
//    }
}