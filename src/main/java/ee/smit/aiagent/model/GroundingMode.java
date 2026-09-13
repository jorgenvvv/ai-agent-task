package ee.smit.aiagent.model;

import java.util.Locale;

public enum GroundingMode {
    LEXICAL,
    HYBRID;

    public static GroundingMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "lexical" -> LEXICAL;
            case "hybrid" -> HYBRID;
            default -> HYBRID;
        };
    }
}
