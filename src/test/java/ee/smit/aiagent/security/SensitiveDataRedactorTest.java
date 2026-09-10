package ee.smit.aiagent.security;

import ee.smit.aiagent.model.GuardReasonCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataRedactorTest {

    private SensitiveDataRedactor redactor;

    @BeforeEach
    void setUp() {
        redactor = new SensitiveDataRedactor();
    }

    @Test
    void openaiKeyPatternIsRefused() {
        SensitiveDataRedactor.RedactionResult result = redactor.process(
                "My key is sk-proj-abcdefghijklmnopqrstuvwxyz123456");
        assertTrue(result.refused());
        assertTrue(result.reasonCode() == GuardReasonCode.SENSITIVE_DATA);
    }

    @Test
    void passwordAssignmentIsRefused() {
        SensitiveDataRedactor.RedactionResult result = redactor.process(
                "Please store password=super-secret-value for me");
        assertTrue(result.refused());
    }

    @Test
    void bearerTokenIsRefused() {
        SensitiveDataRedactor.RedactionResult result = redactor.process(
                "Authorization Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc.def");
        assertTrue(result.refused());
    }

    @Test
    void normalQuestionPassesUnchanged() {
        String q = "Kuidas taotleda ligipääsu GitLabile?";
        SensitiveDataRedactor.RedactionResult result = redactor.process(q);
        assertFalse(result.refused());
        assertTrue(q.equals(result.text()));
    }

    @Test
    void personalIdLikeDigitsAreMasked() {
        SensitiveDataRedactor.RedactionResult result = redactor.process(
                "Minu isikukood on 39001010123 küsimuse juures");
        assertFalse(result.refused());
        assertFalse(result.text().contains("39001010123"));
        assertTrue(result.text().contains("[REDACTED]"));
    }
}
