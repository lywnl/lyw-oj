package com.lyw.api.reconcile;

import com.lyw.common.core.domain.Result;
import com.lyw.common.core.utils.InternalReconcilePath;
import org.springframework.context.annotation.Conditional;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@Conditional(ValidatedReconcileClientCondition.class)
@FeignClient(contextId = "questionReconcile", name = "question-reconcile",
        url = "${question.reconcile.target-url}", configuration = QuestionReconcileFeignConfiguration.class)
public interface RemoteQuestionReconcileService {
    @PostMapping(InternalReconcilePath.PATH)
    Result<ReconcileResult> reconcile(@RequestHeader(InternalReconcilePath.TOKEN_HEADER) String token);
}
