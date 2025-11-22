package com.lyw.job.service;

import com.lyw.job.domain.message.MessageText;

import java.util.List;

public interface IMessageTextService {
    Boolean batchInsert(List<MessageText> messageTextList);

}
