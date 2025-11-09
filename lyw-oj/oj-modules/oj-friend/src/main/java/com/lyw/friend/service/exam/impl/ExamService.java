package com.lyw.friend.service.exam.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.domain.exam.vo.ExamVO;
import com.lyw.friend.manager.ExamCacheManager;
import com.lyw.friend.mapper.exam.ExamMapper;
import com.lyw.friend.service.exam.IExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExamService implements IExamService {

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

        return TableDataInfo.success(examVOList, total);
    }


}
