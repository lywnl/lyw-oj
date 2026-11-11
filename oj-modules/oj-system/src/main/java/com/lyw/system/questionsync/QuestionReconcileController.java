package com.lyw.system.questionsync;

import com.lyw.api.reconcile.ReconcileResult;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.InternalReconcilePath;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuestionReconcileController {
    private final QuestionEsSyncRunner runner;
    public QuestionReconcileController(QuestionEsSyncRunner runner) { this.runner = runner; }

    @PostMapping(InternalReconcilePath.PATH)
    public ResponseEntity<Result<ReconcileResult>> reconcile() {
        try {
            return ResponseEntity.ok(Result.ok(runner.requestReconciliation().await()));
        } catch (ReconciliationException failure) {
            return ResponseEntity.status(failure.status())
                    .body(Result.fail(ResultCode.FAILED.getCode(), failure.getMessage()));
        }
    }
}
