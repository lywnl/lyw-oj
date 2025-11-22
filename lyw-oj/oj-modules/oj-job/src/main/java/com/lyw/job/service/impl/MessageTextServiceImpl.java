package com.lyw.job.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lyw.job.domain.message.MessageText;
import com.lyw.job.mapper.message.MessageTextMapper;
import com.lyw.job.service.IMessageTextService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageTextServiceImpl extends ServiceImpl<MessageTextMapper, MessageText> implements IMessageTextService {

    @Override
    public Boolean batchInsert(List<MessageText> messageTextList) {
        return saveBatch(messageTextList);
    }

}
