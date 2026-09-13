package ee.smit.aiagent.model;

public record GroundingJudgeResponse(
        boolean grounded,
        String reason
) {
}
