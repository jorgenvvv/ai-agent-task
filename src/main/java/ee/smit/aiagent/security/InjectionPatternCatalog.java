package ee.smit.aiagent.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class InjectionPatternCatalog {

    private static final List<Pattern> PATTERNS = List.of(
            compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions?"),
            compile("forget\\s+(all\\s+)?(your\\s+)?(rules|instructions)"),
            compile("unusta\\s+(kõik\\s+|koik\\s+)?(oma\\s+)?reeglid"),
            compile("unusta\\s+kõik\\s+reeglid"),
            compile("ignoreeri\\s+(kõiki\\s+|koiki\\s+)?(eelmisi\\s+)?juhiseid"),
            compile("enne\\s+vastamist\\s+unusta"),
            compile("you\\s+are\\s+now\\b"),
            compile("\\bact\\s+as\\b"),
            compile("sa\\s+oled\\s+nüüd\\b"),
            compile("sa\\s+oled\\s+nuud\\b"),
            compile("sa\\s+ei\\s+ole\\s+enam\\b"),
            compile("vaba\\s+assistent"),
            compile("(?m)^\\s*system\\s*:"),
            compile("\\bsystem\\s*:\\s*uus\\s+reegel"),
            compile("\\bsystem\\s*:\\s*new\\s+rule"),
            compile("system\\s+prompt"),
            compile("korda\\s+sõna[- ]?sõnalt"),
            compile("korda\\s+sona[- ]?sonalt"),
            compile("repeat\\s+(all\\s+)?(previous\\s+)?messages"),
            compile("list\\s+all\\s+available\\s+tools"),
            compile("tool(s)?\\s+(definitions?|parameters?|schema)"),
            compile("sisemisi\\s+juhiseid"),
            compile("reveal\\s+(your\\s+)?(system|hidden)\\s+(prompt|instructions?)"),
            compile("\\.\\./"),
            compile("\\.\\.\\\\"),
            compile("/etc/passwd"),
            compile("etc\\\\passwd"),
            compile("\\bdan\\b"),
            compile("\\bjailbreak\\b"),
            compile("without\\s+(any\\s+)?(restrictions?|limits?|guardrails?)"),
            compile("ilma\\s+piiranguteta"),
            compile("do\\s+anything\\s+now")
    );

    private InjectionPatternCatalog() {
    }

    public static List<Pattern> patterns() {
        return PATTERNS;
    }

    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question
                .toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
