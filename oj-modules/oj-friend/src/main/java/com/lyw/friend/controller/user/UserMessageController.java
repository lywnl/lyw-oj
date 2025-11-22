package com.lyw.friend.controller.user;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.common.core.domain.dto.PageQueryDTO;
import com.lyw.friend.service.user.IUserMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/message")
public class UserMessageController extends BaseController {

    @Autowired
    IUserMessageService userMessageService;

    @GetMapping("/list")
    public TableDataInfo list(PageQueryDTO pageQueryDTO) {
        return userMessageService.list(pageQueryDTO);
    }
}
