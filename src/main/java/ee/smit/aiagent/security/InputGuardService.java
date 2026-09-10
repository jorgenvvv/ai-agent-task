package ee.smit.aiagent.security;

import ee.smit.aiagent.model.GuardDecision;
import ee.smit.aiagent.model.GuardReasonCode;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class InputGuardService {

    public GuardDecision analyze(String question) {
        if (question == null || question.isBlank()) {
            return GuardDecision.allow();
        }

        String normalized = InjectionPatternCatalog.normalize(question);
        for (Pattern pattern : InjectionPatternCatalog.patterns()) {
            if (pattern.matcher(normalized).find()) {
                return GuardDecision.refuse(GuardReasonCode.INJECTION);
            }
        }
        return GuardDecision.allow();
    }
}
