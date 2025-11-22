package com.lyw.judge.service;

import com.lyw.judge.domain.SandBoxExecuteResult;

import java.util.List;

public interface ISandboxPoolService {

    SandBoxExecuteResult exeJavaCode(Long userId, String userCode, List<String> inputList);

}
