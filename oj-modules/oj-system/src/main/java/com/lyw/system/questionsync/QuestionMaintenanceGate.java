package com.lyw.system.questionsync;

import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

/** 逐次拒绝维护期间的新写请求；既有请求的排空由实际维护流程确认。 */
@Component
public class QuestionMaintenanceGate {
    private final Environment environment;
    public QuestionMaintenanceGate(Environment environment) { this.environment = environment; }
    public boolean enabled() { return environment.getProperty("question.maintenance.enabled", Boolean.class, false); }
    public <T> T write(Supplier<T> operation) {
        if (enabled()) throw new ServiceException(ResultCode.QUESTION_MAINTENANCE);
        return operation.get();
    }
}
