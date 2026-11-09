package com.lyw.api.domain.dto;

/** 缺少业务身份的消息不能通过临时生成 ID 继续执行。 */
public final class JudgeSubmissionIdentity {
    private JudgeSubmissionIdentity() { }

    public static boolean isValid(JudgeSubmitDTO submission) {
        return submission != null && positive(submission.getSubmitId())
                && positive(submission.getUserId()) && positive(submission.getQuestionId())
                && (submission.getExamId() == null || positive(submission.getExamId()));
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
