package com.lyw.friend.manager;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.github.pagehelper.PageHelper;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.domain.dto.PageQueryDTO;
import com.lyw.common.redis.service.RedisService;
import com.lyw.friend.domain.message.vo.MessageTextVO;
import com.lyw.friend.mapper.message.MessageTextMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MessageCacheManager {

    @Autowired
    private RedisService redisService;

    @Autowired
    private MessageTextMapper messageTextMapper;

    public Long getListSize(Long userId) {
        String userMsgListKey = getUserMsgListKey(userId);
        return redisService.getListSize(userMsgListKey);
    }

    public void refreshCache(Long userId) {
        List<MessageTextVO> messageTextVOList = messageTextMapper.selectUserMsgList(userId);
        if (CollectionUtil.isEmpty(messageTextVOList)) {
            return ;
        }

        List<Long> textIdList = messageTextVOList.stream().map(MessageTextVO::getTextId).toList();
        String userMsgListKey = getUserMsgListKey(userId);
        redisService.rightPushAll(userMsgListKey, textIdList);
        Map<String, MessageTextVO> messageTextVOMap = new HashMap<>();
        for (MessageTextVO messageTextVO : messageTextVOList) {
            messageTextVOMap.put(getMsgDetailKey(messageTextVO.getTextId()), messageTextVO);
        }

        redisService.multiSet(messageTextVOMap);
    }

    public List<MessageTextVO> getMessageTextVOList(PageQueryDTO pageQueryDTO, Long userId) {
        int start = (pageQueryDTO.getPageNum() - 1) * pageQueryDTO.getPageSize();
        int end = start + pageQueryDTO.getPageSize() - 1; //下标需要 -1
        String userMsgListKey = getUserMsgListKey(userId);
        List<Long> messageTextIdList = redisService.getCacheListByRange(userMsgListKey, start, end, Long.class);
        List<MessageTextVO> messageTextVOList = assembleMessageTextVOList(messageTextIdList);
        if (CollectionUtil.isEmpty(messageTextVOList)) {
            //说明redis中数据可能有问题 从数据库中查数据并且重新刷新缓存
            messageTextVOList = getMsgTextVOListByDB(pageQueryDTO, userId); //从数据库中获取数据
            refreshCache(userId);
        }

        return messageTextVOList;
    }


    private List<MessageTextVO> assembleMessageTextVOList(List<Long> messageTextIdList) {
        if (CollectionUtil.isEmpty(messageTextIdList)) {
            //说明redis中没有数据 从数据库中查数据并且重新刷新缓存
            return null;
        }

        List<String> detailKeyList = new ArrayList<>();
        for (Long messageTextId : messageTextIdList) {
            detailKeyList.add(getMsgDetailKey(messageTextId));
        }

        List<MessageTextVO> messageTextVOList = redisService.multiGet(detailKeyList, MessageTextVO.class);
        CollUtil.removeNull(messageTextVOList);
        if (CollectionUtil.isEmpty(messageTextVOList) || messageTextVOList.size() != messageTextIdList.size()) {
            //说明redis中数据有问题 从数据库中查数据并且重新刷新缓存
            return null;
        }

        return messageTextVOList;
    }
    private List<MessageTextVO> getMsgTextVOListByDB(PageQueryDTO pageQueryDTO, Long userId) {
        PageHelper.startPage(pageQueryDTO.getPageNum(), pageQueryDTO.getPageSize());
        return messageTextMapper.selectUserMsgList(userId);
    }

    private String getUserMsgListKey(Long userId) {
        return CacheConstants.USER_MESSAGE_LIST + userId;
    }

    private String getMsgDetailKey(Long textId) {
        return CacheConstants.MESSAGE_DETAIL + textId;
    }



}
