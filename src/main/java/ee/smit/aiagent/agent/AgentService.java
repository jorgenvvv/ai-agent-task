package ee.smit.aiagent.agent;

import ee.smit.aiagent.knowledge.ToolSourcesBuffer;
import ee.smit.aiagent.model.AgentLlmResponse;
import ee.smit.aiagent.model.AskRequest;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.model.SourceDto;
import ee.smit.aiagent.model.GuardDecision;
import ee.smit.aiagent.model.GuardReasonCode;
import ee.smit.aiagent.security.InputGuardService;
import ee.smit.aiagent.security.SensitiveDataRedactor;
import ee.smit.aiagent.security.SessionIdHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String SOURCE_CITATION_MARKER = "[allikas:";
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,100}$");
    private static final Pattern CITATION_PATTERN = Pattern.compile(
            "\\[\\s*allikas\\s*:\\s*([^\\]]+?)\\s*\\]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final double MIN_GROUNDING_OVERLAP = 0.40;
    private static final int MIN_GROUNDED_TOKENS = 2;

    private static final String DEFAULT_REFUSAL_ANSWER =
            "Kahjuks ei saa ma selle päringuga jätkata. Palun esita tavaline küsimus IT teenuste teadmusbaasi kohta.";
    private static final String UNIVERSAL_REFUSAL_REASON = "Keeldutud turvapoliitika alusel";
    static final String PROVIDER_FAILURE_MESSAGE = "AI provider request failed";

    private static final Pattern FUNCTION_CATALOG_PATTERN = Pattern.compile(
            "\\\"functions\\\"\\s*:\\s*\\[|\\\"name\\\"\\s*:\\s*\\\"(list_topics|search_knowledge|get_document)\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL_NAME_LEAK_PATTERN = Pattern.compile(
            "\\b(list_topics|search_knowledge|get_document)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "or",
            "that", "the", "to", "was", "were", "with", "this", "are", "not", "no", "md", "vt", "nt", "sh",
            "ja", "ning", "või", "ei", "on", "oli", "ole", "olla", "et", "kui", "ka", "nii", "siis",
            "see", "need", "kuid", "aga", "sest", "kuna", "mis", "kes", "kus", "kuidas",
            "oma", "üle", "alla", "läbi", "peale", "enne", "pärast", "vahel",
            "me", "te", "nad", "ta", "ma", "sa", "mul", "sul", "tal", "meil", "teil", "neil",
            "minu", "sinu", "tema", "meie", "teie", "nende", "enda");

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ToolSourcesBuffer sourcesBuffer;
    private final InputGuardService inputGuardService;
    private final SensitiveDataRedactor sensitiveDataRedactor;
    private final String openAiApiKey;
    private final boolean sessionEnabled;

    public AgentService(
            ChatClient chatClient,
            ChatMemory chatMemory,
            ToolSourcesBuffer sourcesBuffer,
            InputGuardService inputGuardService,
            SensitiveDataRedactor sensitiveDataRedactor,
            @Value("${spring.ai.openai.api-key:}") String openAiApiKey,
            @Value("${app.agent.session.enabled:true}") boolean sessionEnabled) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.sourcesBuffer = sourcesBuffer;
        this.inputGuardService = inputGuardService;
        this.sensitiveDataRedactor = sensitiveDataRedactor;
        this.openAiApiKey = openAiApiKey;
        this.sessionEnabled = sessionEnabled;
    }

    public AskResponse ask(AskRequest request) {
        sourcesBuffer.clear();

        String question = request.question();
        int questionLength = question != null ? question.length() : 0;
        String sessionHash = SessionIdHasher.hash(request.sessionId());

        GuardDecision guard = inputGuardService.analyze(question);
        if (!guard.allowed()) {
            logRejected(guard.reasonCode(), sessionHash, questionLength);
            return refusedResponse(guard.reasonCode());
        }

        SensitiveDataRedactor.RedactionResult redaction = sensitiveDataRedactor.process(question);
        if (redaction.refused()) {
            logRejected(GuardReasonCode.SENSITIVE_DATA, sessionHash, questionLength);
            return refusedResponse(GuardReasonCode.SENSITIVE_DATA);
        }

        ensureApiKeyConfigured();

        String sessionKey = resolveSessionKey(request.sessionId());
        String userMessage = redaction.text();

        AgentLlmResponse llmResponse;
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .user(userMessage);

            if (sessionKey != null) {
                spec = spec
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionKey));
            }

            llmResponse = spec.call().entity(AgentLlmResponse.class);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw providerFailed(e);
        }

        if (llmResponse == null) {
            throw providerFailed(null);
        }

        List<SourceDto> sources = sourcesBuffer.snapshot();
        return applyPostRules(llmResponse, sources);
    }

    private void logRejected(GuardReasonCode reasonCode, String sessionHash, int length) {
        log.warn("prompt_injection_rejected sessionHash={} reasonCode={} length={}",
                sessionHash, reasonCode, length);
    }

    static AskResponse refusedResponse(GuardReasonCode reasonCode) {
        return sanitizedRefusal();
    }

    String resolveSessionKey(String sessionId) {
        if (!sessionEnabled) {
            return null;
        }
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String trimmed = sessionId.trim();
        if (!SESSION_ID_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sessionId must match [a-zA-Z0-9_-]{1,100}");
        }
        return trimmed;
    }


    private ResponseStatusException providerFailed(Throwable cause) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        if (cause != null) {
            log.error("AI provider request failed correlationId={} detail={}",
                    correlationId, rootMessage(cause), cause);
        } else {
            log.error("AI provider request failed correlationId={} detail=empty_response",
                    correlationId);
        }
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                PROVIDER_FAILURE_MESSAGE,
                cause);
    }

    private void ensureApiKeyConfigured() {
        if (!StringUtils.hasText(openAiApiKey)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenAI API key is not configured (set OPENAI_API_KEY)");
        }
    }

    static AskResponse applyPostRules(AgentLlmResponse llm, List<SourceDto> toolSources) {
        List<SourceDto> sources = sanitizeSources(toolSources);
        boolean refused = llm.refused();
        String answer = llm.answer() != null ? llm.answer() : "";
        String confidence = normalizeConfidence(llm.confidence(), refused);

        if (!refused && sources.isEmpty()) {
            refused = true;
        }

        if (!refused) {
            answer = sanitizeCitations(answer, sources);
            if (!isAnswerGrounded(answer, sources) || containsUnsafeOutput(answer)) {
                refused = true;
            }
        }

        if (refused) {
            return sanitizedRefusal();
        }

        if (!StringUtils.hasText(confidence)) {
            confidence = "high";
        }
        answer = ensureSourceCitation(answer, sources);
        return new AskResponse(answer, sources, confidence, false, null);
    }

    static AskResponse sanitizedRefusal() {
        return new AskResponse(DEFAULT_REFUSAL_ANSWER, List.of(), "low", true, UNIVERSAL_REFUSAL_REASON);
    }

    static boolean containsUnsafeOutput(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        if (lower.contains("<tool_result") || lower.contains("audit_marker")) {
            return true;
        }
        if (FUNCTION_CATALOG_PATTERN.matcher(answer).find()) {
            return true;
        }
        if (TOOL_NAME_LEAK_PATTERN.matcher(answer).find()
                && (lower.contains("\"parameters\"")
                || lower.contains("\"arguments\"")
                || lower.contains("\"functions\"")
                || lower.contains("@tool")
                || lower.contains("toolparam"))) {
            return true;
        }
        return false;
    }

    private static List<SourceDto> sanitizeSources(List<SourceDto> toolSources) {
        if (toolSources == null || toolSources.isEmpty()) {
            return List.of();
        }
        List<SourceDto> cleaned = new ArrayList<>();
        for (SourceDto source : toolSources) {
            if (source == null) {
                continue;
            }
            if (!StringUtils.hasText(source.file())) {
                continue;
            }
            if (!StringUtils.hasText(source.excerpt())) {
                continue;
            }
            cleaned.add(source);
        }
        return List.copyOf(cleaned);
    }

    static String sanitizeCitations(String answer, List<SourceDto> sources) {
        if (answer == null || answer.isEmpty()) {
            return answer == null ? "" : answer;
        }
        Set<String> allowed = allowedSourceFiles(sources);
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String cited = matcher.group(1) != null ? matcher.group(1).trim() : "";
            if (isAllowedCitation(cited, allowed)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString().replaceAll(" +", " ").replaceAll(" +([.,;:!?])", "$1").trim();
    }

    private static Set<String> allowedSourceFiles(List<SourceDto> sources) {
        Set<String> allowed = new HashSet<>();
        if (sources == null) {
            return allowed;
        }
        for (SourceDto source : sources) {
            if (source != null && StringUtils.hasText(source.file())) {
                allowed.add(source.file().trim().toLowerCase(Locale.ROOT));
            }
        }
        return allowed;
    }

    private static boolean isAllowedCitation(String citedFile, Set<String> allowed) {
        if (!StringUtils.hasText(citedFile) || allowed.isEmpty()) {
            return false;
        }
        String normalized = citedFile.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(normalized)) {
            return true;
        }
        for (String file : allowed) {
            if (file.endsWith("/" + normalized) || normalized.endsWith("/" + file)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAnswerGrounded(String answer, List<SourceDto> sources) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        String withoutCitations = CITATION_PATTERN.matcher(answer == null ? "" : answer).replaceAll(" ");
        List<String> answerTokens = significantTokens(withoutCitations);
        if (answerTokens.isEmpty()) {
            return false;
        }

        Set<String> sourceTokens = new HashSet<>();
        StringBuilder sourceBlob = new StringBuilder();
        for (SourceDto source : sources) {
            if (source == null) {
                continue;
            }
            if (StringUtils.hasText(source.excerpt())) {
                sourceTokens.addAll(significantTokens(source.excerpt()));
                sourceBlob.append(' ').append(source.excerpt());
            }
            if (StringUtils.hasText(source.title())) {
                sourceTokens.addAll(significantTokens(source.title()));
                sourceBlob.append(' ').append(source.title());
            }
            if (StringUtils.hasText(source.file())) {
                sourceTokens.addAll(significantTokens(source.file().replace('.', ' ')));
                sourceBlob.append(' ').append(source.file());
            }
        }
        String blob = sourceBlob.toString().toLowerCase(Locale.ROOT);

        int matched = 0;
        for (String token : answerTokens) {
            if (tokenSupported(token, sourceTokens, blob)) {
                matched++;
            }
        }

        double ratio = (double) matched / (double) answerTokens.size();
        if (answerTokens.size() <= 3) {
            return matched >= Math.min(MIN_GROUNDED_TOKENS, answerTokens.size())
                    && ratio >= MIN_GROUNDING_OVERLAP;
        }
        return matched >= MIN_GROUNDED_TOKENS && ratio >= MIN_GROUNDING_OVERLAP;
    }

    private static boolean tokenSupported(String token, Set<String> sourceTokens, String sourceBlob) {
        if (sourceTokens.contains(token)) {
            return true;
        }
        if (sourceBlob.contains(token)) {
            return true;
        }
        for (String sourceToken : sourceTokens) {
            if (tokensLooselyMatch(token, sourceToken)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tokensLooselyMatch(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int min = Math.min(a.length(), b.length());
        if (min < 4) {
            return false;
        }
        return a.startsWith(b) || b.startsWith(a);
    }

    static List<String> significantTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 2) {
                continue;
            }
            if (STOP_WORDS.contains(token)) {
                continue;
            }
            if (token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }
        return List.copyOf(tokens);
    }

    private static String ensureSourceCitation(String answer, List<SourceDto> sources) {
        if (sources.isEmpty()) {
            return answer;
        }
        String text = answer == null ? "" : answer;
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains(SOURCE_CITATION_MARKER)) {
            return text;
        }
        for (SourceDto source : sources) {
            if (source.file() != null && lower.contains(source.file().toLowerCase(Locale.ROOT))) {
                return text;
            }
        }
        String firstFile = sources.getFirst().file();
        String suffix = " [allikas: " + firstFile + "]";
        return text.stripTrailing() + suffix;
    }

    private static String normalizeConfidence(String confidence, boolean refused) {
        if (refused) {
            return "low";
        }
        if (confidence == null || confidence.isBlank()) {
            return "low";
        }
        String c = confidence.trim().toLowerCase();
        if ("high".equals(c) || "low".equals(c)) {
            return c;
        }
        return "low";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null && !msg.isBlank() ? msg : cur.getClass().getSimpleName();
    }
}
