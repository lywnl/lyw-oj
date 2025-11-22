package com.lyw.job.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lyw.job.domain.message.Message;
import com.lyw.job.mapper.message.MessageMapper;
import com.lyw.job.service.IMessageService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements IMessageService {

    @Override
    public Boolean batchInsert(List<Message> messageList) {
        return saveBatch(messageList);
    }

}
