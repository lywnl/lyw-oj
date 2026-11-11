package com.lyw.job.handler;

import com.lyw.api.reconcile.QuestionReconcileProperties;
import com.lyw.api.reconcile.RemoteQuestionReconcileService;
import com.lyw.api.reconcile.QuestionReconcileFeignConfiguration.RemoteReconcileException;
import com.lyw.common.core.enums.ResultCode;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;

public class QuestionIndexReconcileJob {
    private final RemoteQuestionReconcileService remote;
    private final QuestionReconcileProperties properties;

    public QuestionIndexReconcileJob(RemoteQuestionReconcileService remote, QuestionReconcileProperties properties) {
        this.remote = remote;
        this.properties = properties;
    }

    @XxlJob("questionIndexReconcileHandler")
    public void questionIndexReconcileHandler() {
        try {
            var response = remote.reconcile(properties.getInternalToken());
            if (response == null || response.getCode() != ResultCode.SUCCESS.getCode() || response.getData() == null) {
                XxlJobHelper.handleFail("对账未成功完成，请查看同步实例状态");
                return;
            }
            var result = response.getData();
            XxlJobHelper.handleSuccess("对账完成，差异=" + result.differences() + "，修复=" + result.repaired()
                    + "，耗时毫秒=" + result.durationMillis());
        } catch (RemoteReconcileException failure) {
            XxlJobHelper.handleFail("对账内部调用失败，HTTP=" + failure.getStatus()
                    + "；请核查同步实例及执行结果后再手动重试");
        } catch (Exception failure) {
            // 不记录远程响应、URL、请求头或异常堆栈中的敏感配置。
            XxlJobHelper.handleFail("对账调用失败，类型=" + failure.getClass().getSimpleName()
                    + "；请核查同步实例及执行结果后再手动重试");
        }
    }
}
