package com.lyw.job.service;

import com.lyw.job.domain.message.Message;

import java.util.List;

public interface IMessageService {
    Boolean batchInsert(List<Message> messageList);

}
