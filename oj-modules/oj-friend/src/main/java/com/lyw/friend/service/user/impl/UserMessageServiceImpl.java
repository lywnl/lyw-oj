package com.lyw.friend.service.user.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.common.core.domain.dto.PageQueryDTO;
import com.lyw.common.core.enums.ExamListType;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.friend.domain.exam.vo.ExamVO;
import com.lyw.friend.domain.message.vo.MessageTextVO;
import com.lyw.friend.manager.MessageCacheManager;
import com.lyw.friend.mapper.message.MessageTextMapper;
import com.lyw.friend.service.user.IUserMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserMessageServiceImpl implements IUserMessageService {

    @Autowired
    private MessageCacheManager messageCacheManager;

    @Autowired
    private MessageTextMapper messageTextMapper;

    @Override
    public TableDataInfo list(PageQueryDTO pageQueryDTO) {
        Long userId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        Long total = messageCacheManager.getListSize(userId);

        List<MessageTextVO> messageTextVOList;
        if (total == null || total == 0) {
            //从数据库中查询我的消息列表
            PageHelper.startPage(pageQueryDTO.getPageNum(), pageQueryDTO.getPageSize());
            messageTextVOList = messageTextMapper.selectUserMsgList(userId);
            messageCacheManager.refreshCache(userId);
            total = new PageInfo<>(messageTextVOList).getTotal();
        } else {
            messageTextVOList = messageCacheManager.getMessageTextVOList(pageQueryDTO, userId);
        }

        if (CollectionUtil.isEmpty(messageTextVOList)) {
            return TableDataInfo.empty();
        }

        return TableDataInfo.success(messageTextVOList, total);
    }
}
