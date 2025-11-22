package com.lyw.judge.service;

import com.lyw.judge.domain.SandBoxExecuteResult;

import java.util.List;

public interface ISandboxService {

    SandBoxExecuteResult exeJavaCode(Long userId, String userCode, List<String> inputList);

}
