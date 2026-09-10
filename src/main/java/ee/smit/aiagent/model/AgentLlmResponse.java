package ee.smit.aiagent.model;

public record AgentLlmResponse(
        String answer,
        boolean refused,
        String refusalReason,
        String confidence
) {
}
