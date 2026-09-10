package ee.smit.aiagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @NotBlank(message = "question must not be blank")
        @Size(max = 2000, message = "question must be at most 2000 characters")
        String question,

        @Size(max = 100, message = "sessionId must be at most 100 characters")
        String sessionId
) {
}
