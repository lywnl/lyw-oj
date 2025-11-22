package com.lyw.friend.service.user;

import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.common.core.domain.dto.PageQueryDTO;

public interface IUserMessageService {
    TableDataInfo list(PageQueryDTO pageQueryDTO);

}
