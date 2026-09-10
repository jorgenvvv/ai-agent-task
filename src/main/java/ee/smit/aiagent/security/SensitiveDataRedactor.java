package ee.smit.aiagent.security;

import ee.smit.aiagent.model.GuardReasonCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SensitiveDataRedactor {

    public record RedactionResult(boolean refused, GuardReasonCode reasonCode, String text) {
        public static RedactionResult ok(String text) {
            return new RedactionResult(false, null, text);
        }

        public static RedactionResult refuse() {
            return new RedactionResult(true, GuardReasonCode.SENSITIVE_DATA, null);
        }
    }

    private static final List<Pattern> REFUSE_PATTERNS = List.of(
            Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{10,}\\b"),
            Pattern.compile("(?i)\\bpassword\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)\\bpasswd\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)\\bapi[_-]?key\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)\\bsecret\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9\\-._~+/]+=*")
    );

    private static final List<Pattern> MASK_PATTERNS = List.of(
            Pattern.compile("\\b[1-6]\\d{10}\\b")
    );

    private static final String MASK = "[REDACTED]";

    public RedactionResult process(String question) {
        if (question == null || question.isBlank()) {
            return RedactionResult.ok(question == null ? "" : question);
        }

        for (Pattern pattern : REFUSE_PATTERNS) {
            if (pattern.matcher(question).find()) {
                return RedactionResult.refuse();
            }
        }

        String redacted = question;
        for (Pattern pattern : MASK_PATTERNS) {
            Matcher matcher = pattern.matcher(redacted);
            redacted = matcher.replaceAll(MASK);
        }
        return RedactionResult.ok(redacted);
    }
}
