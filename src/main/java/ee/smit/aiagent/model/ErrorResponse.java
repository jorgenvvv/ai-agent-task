package ee.smit.aiagent.model;

public record ErrorResponse(
        int status,
        String error,
        String message
) {
}
