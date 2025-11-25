package com.lyw.friend.test;

import com.lyw.common.core.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/nginx")
@Slf4j
public class NginxTestController {

    @GetMapping("/info")
    public Result<Void> info() {
        log.info("负载均衡测试");
        return Result.ok();
    }

}
