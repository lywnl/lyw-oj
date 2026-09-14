package com.lyw.judge.service.impl;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;

import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.constants.JudgeConstants;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.callback.DockerStartResultCallback;
import com.lyw.judge.callback.StatisticsCallback;
import com.lyw.judge.config.DockerSandBoxPool;
import com.lyw.judge.domain.CompileResult;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.service.ISandboxPoolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SandboxPoolServiceImpl implements ISandboxPoolService {

    @Autowired
    private DockerSandBoxPool sandBoxPool;

    @Autowired
    private DockerClient dockerClient;

    @Value("${sandbox.limit.time:5}")
    private Long timeLimit;

    @Override
    public SandBoxExecuteResult exeJavaCode(Long userId, String userCode, List<String> inputList) {
        JudgeTask task = new JudgeTask(userId, userCode, inputList,
                containerId -> executeInContainer(containerId, userCode, inputList), null);
        if (!sandBoxPool.submit(task)) {
            return SandBoxExecuteResult.fail(CodeRunStatus.SYSTEM_BUSY, CodeRunStatus.SYSTEM_BUSY.getMsg());
        }
        try {
            return task.getFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private SandBoxExecuteResult executeInContainer(String containerId, String userCode,
                                                    List<String> inputList) {
        String userCodeFileName = createUserCodeFile(containerId, userCode);
        try {
            CompileResult compileResult = compileCodeByDocker(containerId);
            if (!compileResult.isCompiled()) {
                FileUtil.del(userCodeFileName);
                return SandBoxExecuteResult.fail(CodeRunStatus.COMPILE_FAILED, compileResult.getExeMessage());
            }
            return executeJavaCodeByDocker(containerId, userCodeFileName, inputList);
        } catch (Exception e) {
            FileUtil.del(userCodeFileName);
            throw new RuntimeException(e);
        }
    }

    private String createUserCodeFile(String containerId, String userCode) {
        String codeDir = sandBoxPool.getCodeDir(containerId);
        log.info("user-pool路径信息：{}", codeDir);
        String userCodeFileName = codeDir + File.separator + JudgeConstants.USER_CODE_JAVA_CLASS_NAME;
        if (FileUtil.exist(userCodeFileName)) {
            FileUtil.del(userCodeFileName);
        }
        FileUtil.writeString(userCode, userCodeFileName, Constants.UTF8);
        return userCodeFileName;
    }

    private CompileResult compileCodeByDocker(String containerId) {
        String cmdId = createExecCmd(JudgeConstants.DOCKER_JAVAC_CMD, null, containerId);
        DockerStartResultCallback resultCallback = new DockerStartResultCallback();
        CompileResult compileResult = new CompileResult();
        try {
            dockerClient.execStartCmd(cmdId)
                    .exec(resultCallback)
                    .awaitCompletion();
            if (CodeRunStatus.FAILED.equals(resultCallback.getCodeRunStatus())) {
                compileResult.setCompiled(false);
                compileResult.setExeMessage(resultCallback.getErrorMessage());
            } else {
                compileResult.setCompiled(true);
            }
            return compileResult;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private SandBoxExecuteResult executeJavaCodeByDocker(String containerId, String userCodeFileName, List<String> inputList) {
        List<String> outList = new ArrayList<>();
        long maxMemory = 0L;
        long maxUseTime = 0L;
        for (String inputArgs : inputList) {
            String cmdId = createExecCmd(JudgeConstants.DOCKER_JAVA_EXEC_CMD, inputArgs, containerId);
            StopWatch stopWatch = new StopWatch();
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            StatisticsCallback statisticsCallback = statsCmd.exec(new StatisticsCallback());
            stopWatch.start();
            DockerStartResultCallback resultCallback = new DockerStartResultCallback();
            try {
                boolean completed = dockerClient.execStartCmd(cmdId)
                        .exec(resultCallback)
                        .awaitCompletion(timeLimit, TimeUnit.SECONDS);
                if (!completed) {
                    FileUtil.del(userCodeFileName);
                    return SandBoxExecuteResult.fail(CodeRunStatus.OUT_OF_TIME, outList, maxMemory, maxUseTime);
                }
                if (CodeRunStatus.FAILED.equals(resultCallback.getCodeRunStatus())) {
                    FileUtil.del(userCodeFileName);
                    return SandBoxExecuteResult.fail(CodeRunStatus.NOT_ALL_PASSED);
                }
            } catch (InterruptedException e) {
                FileUtil.del(userCodeFileName);
                throw new RuntimeException(e);
            } finally {
                statsCmd.close();
            }
            stopWatch.stop();
            long userTime = stopWatch.getLastTaskTimeMillis();
            maxUseTime = Math.max(userTime, maxUseTime);
            Long memory = statisticsCallback.getMaxMemory();
            if (memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
            outList.add(resultCallback.getMessage().trim());
        }
        FileUtil.del(userCodeFileName);
        return getSanBoxResult(inputList, outList, maxMemory, maxUseTime);
    }

    private String createExecCmd(String[] javaCmdArr, String inputArgs, String containerId) {
        if (!StrUtil.isEmpty(inputArgs)) {
            String[] inputArray = inputArgs.split(" ");
            javaCmdArr = ArrayUtil.append(JudgeConstants.DOCKER_JAVA_EXEC_CMD, inputArray);
        }
        ExecCreateCmdResponse cmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(javaCmdArr)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .exec();
        return cmdResponse.getId();
    }

    private SandBoxExecuteResult getSanBoxResult(List<String> inputList, List<String> outList,
                                                 long maxMemory, long maxUseTime) {
        if (inputList.size() != outList.size()) {
            return SandBoxExecuteResult.fail(CodeRunStatus.NOT_ALL_PASSED, outList, maxMemory, maxUseTime);
        }
        return SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, outList, maxMemory, maxUseTime);
    }
}
