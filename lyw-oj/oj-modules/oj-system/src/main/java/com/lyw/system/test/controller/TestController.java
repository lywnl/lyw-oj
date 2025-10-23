package com.lyw.system.test.controller;

import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.system.test.domain.LoginTestDTO;
import com.lyw.system.test.service.ITestService;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private ITestService testService;

    @GetMapping("/list")
    public List<?> list() {
        return testService.list();
    }

    @GetMapping("/add")
    public String add() {
        return testService.add();
    }

    @GetMapping("/apifoxtest")
    public Result<String> apifoxtest(String apiId) {
        Result result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMsg(ResultCode.SUCCESS.getMsg());
        result.setData("apifox测试" + apiId);

        return result;
    }

    @PostMapping("/testPost")
    public Result<String> testPost(@RequestBody LoginTestDTO loginTestDTO) {
        Result result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMsg(ResultCode.SUCCESS.getMsg());
        result.setData("post测试" + loginTestDTO.getUserAccount());

        return result;
    }
}
