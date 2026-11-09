package com.lyw.api.domain.cache;

import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/** 一个提交只有一个缓存；等待状态不参与消费者幂等判断。 */
@Getter
@Setter
public class JudgeResultCache {
    private Long userId;
    private Long submitId;
    private String cacheState;
    private UserQuestionResultVO result;

    public static JudgeResultCache waiting(Long userId, Long submitId) {
        return create(userId, submitId, "WAITING", UserQuestionResultVO.noResult(submitId));
    }

    public static JudgeResultCache error(Long userId, Long submitId, String message) {
        return create(userId, submitId, "SYSTEM_ERROR", UserQuestionResultVO.systemError(submitId, message));
    }

    public static JudgeResultCache judged(Long userId, UserQuestionResultVO result) {
        if (result == null || result.getResultState() != JudgeResultState.JUDGED
                || !(Objects.equals(result.getPass(), 0) || Objects.equals(result.getPass(), 1))) {
            throw new IllegalArgumentException("只能缓存已持久化的真实判题结果");
        }
        return create(userId, result.getSubmitId(), "JUDGED", result);
    }

    private static JudgeResultCache create(Long userId, Long submitId, String state, UserQuestionResultVO result) {
        if (userId == null || userId <= 0 || submitId == null || submitId <= 0) {
            throw new IllegalArgumentException("缓存缺少有效提交身份");
        }
        JudgeResultCache cache = new JudgeResultCache();
        cache.setUserId(userId);
        cache.setSubmitId(submitId);
        cache.setCacheState(state);
        cache.setResult(result);
        return cache;
    }

    public UserQuestionResultVO checkedResult(Long expectedUserId, Long expectedSubmitId) {
        if (!Objects.equals(userId, expectedUserId) || !Objects.equals(submitId, expectedSubmitId)
                || result == null || !Objects.equals(result.getSubmitId(), expectedSubmitId)) {
            throw new IllegalStateException("缓存提交身份不匹配");
        }
        JudgeResultState expected = switch (cacheState == null ? "" : cacheState) {
            case "WAITING" -> JudgeResultState.NO_RESULT;
            case "SYSTEM_ERROR" -> JudgeResultState.SYSTEM_ERROR;
            case "JUDGED" -> JudgeResultState.JUDGED;
            default -> throw new IllegalStateException("缓存状态无法识别");
        };
        if (result.getResultState() != expected
                || (expected == JudgeResultState.JUDGED
                ? !(Objects.equals(result.getPass(), 0) || Objects.equals(result.getPass(), 1))
                : result.getPass() != null)) {
            throw new IllegalStateException("缓存结果状态不完整");
        }
        return result;
    }
}
