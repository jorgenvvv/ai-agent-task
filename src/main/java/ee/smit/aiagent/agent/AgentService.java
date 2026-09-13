package ee.smit.aiagent.agent;

import ee.smit.aiagent.knowledge.SessionSourcesCache;
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
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern NUMBER_THEN_WORD = Pattern.compile(
            "(\\d+)\\s*([\\p{L}][\\p{L}\\p{N}]{2,})",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MIN_GROUND_WORD_LEN = 4;
    private static final int MIN_GROUND_OVERLAP_PERCENT = 70;
    private static final int MIN_SENTENCE_OVERLAP_PERCENT = 50;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ToolSourcesBuffer sourcesBuffer;
    private final SessionSourcesCache sessionSourcesCache;
    private final InputGuardService inputGuardService;
    private final SensitiveDataRedactor sensitiveDataRedactor;
    private final String openAiApiKey;
    private final boolean sessionEnabled;

    public AgentService(
            ChatClient chatClient,
            ChatMemory chatMemory,
            ToolSourcesBuffer sourcesBuffer,
            SessionSourcesCache sessionSourcesCache,
            InputGuardService inputGuardService,
            SensitiveDataRedactor sensitiveDataRedactor,
            @Value("${spring.ai.openai.api-key:}") String openAiApiKey,
            @Value("${app.agent.session.enabled:true}") boolean sessionEnabled) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.sourcesBuffer = sourcesBuffer;
        this.sessionSourcesCache = sessionSourcesCache;
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
        if (sources.isEmpty() && sessionKey != null) {
            List<SourceDto> cached = sessionSourcesCache.get(sessionKey);
            if (!cached.isEmpty()
                    && StringUtils.hasText(llmResponse.answer())
                    && !Boolean.TRUE.equals(llmResponse.refused())
                    && isGroundedInSources(llmResponse.answer(), cached)) {
                sources = cached;
            }
        }

        AskResponse response = applyPostRules(llmResponse, sources);
        if (sessionKey != null && !response.refused() && !response.sources().isEmpty()) {
            sessionSourcesCache.put(sessionKey, response.sources());
        }
        return response;
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
            if (containsUnsafeOutput(answer)) {
                refused = true;
            } else if (!isGroundedInSources(answer, sources)) {
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

    static boolean isGroundedInSources(String answer, List<SourceDto> sources) {
        if (!StringUtils.hasText(answer) || sources == null || sources.isEmpty()) {
            return false;
        }

        String corpus = sourceCorpus(sources);
        if (corpus.isEmpty()) {
            return false;
        }

        String text = CITATION_PATTERN.matcher(answer).replaceAll(" ");
        text = text.toLowerCase(Locale.ROOT);

        Matcher urls = URL_PATTERN.matcher(text);
        while (urls.find()) {
            String url = urls.group().toLowerCase(Locale.ROOT);
            url = url.replaceAll("[),.;!?]+$", "");
            if (!corpus.contains(url)) {
                return false;
            }
        }

        for (String number : extractNumbers(text)) {
            if (!corpus.contains(number)) {
                return false;
            }
        }

        if (!numberWordPairsSupported(text, corpus)) {
            return false;
        }

        if (!overlapPercentAtLeast(text, corpus, MIN_GROUND_OVERLAP_PERCENT)) {
            return false;
        }

        for (String sentence : splitSentences(text)) {
            if (!isSubstantiveSentence(sentence)) {
                continue;
            }
            if (!overlapPercentAtLeast(sentence, corpus, MIN_SENTENCE_OVERLAP_PERCENT)) {
                return false;
            }
        }

        return true;
    }

    static List<String> splitSentences(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = text.trim();
        String[] raw = SENTENCE_SPLIT.split(normalized);
        List<String> sentences = new ArrayList<>();
        for (String part : raw) {
            if (part != null && !part.isBlank()) {
                sentences.add(part.trim());
            }
        }
        if (sentences.isEmpty() && !normalized.isBlank()) {
            sentences.add(normalized);
        }
        return sentences;
    }

    static List<String> extractNumbers(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    static boolean numberWordPairsSupported(String text, String corpus) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(corpus)) {
            return true;
        }
        Matcher matcher = NUMBER_THEN_WORD.matcher(text);
        while (matcher.find()) {
            String number = matcher.group(1);
            String word = matcher.group(2).toLowerCase(Locale.ROOT);
            if (!corpus.contains(number)) {
                return false;
            }
            if (!tokenSupportedByCorpus(word, corpus)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSubstantiveSentence(String sentence) {
        int significant = 0;
        for (String part : WORD_SPLIT.split(sentence)) {
            if (isGroundToken(part)) {
                significant++;
                if (significant >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isGroundToken(String part) {
        return part != null && part.length() >= MIN_GROUND_WORD_LEN;
    }

    private static boolean overlapPercentAtLeast(String text, String corpus, int minPercent) {
        String[] parts = WORD_SPLIT.split(text);
        int significant = 0;
        int matched = 0;
        for (String part : parts) {
            if (!isGroundToken(part)) {
                continue;
            }
            significant++;
            if (tokenSupportedByCorpus(part, corpus)) {
                matched++;
            }
        }
        if (significant == 0) {
            return false;
        }
        return matched * 100 >= significant * minPercent;
    }

    private static boolean tokenSupportedByCorpus(String token, String corpus) {
        if (corpus.contains(token)) {
            return true;
        }
        if (token.length() <= MIN_GROUND_WORD_LEN) {
            return false;
        }
        int maxDrop = Math.min(3, token.length() - MIN_GROUND_WORD_LEN);
        for (int drop = 1; drop <= maxDrop; drop++) {
            if (corpus.contains(token.substring(0, token.length() - drop))) {
                return true;
            }
        }
        return false;
    }

    private static String sourceCorpus(List<SourceDto> sources) {
        StringBuilder sb = new StringBuilder();
        for (SourceDto source : sources) {
            if (source == null) {
                continue;
            }
            if (StringUtils.hasText(source.file())) {
                sb.append(' ').append(source.file());
            }
            if (StringUtils.hasText(source.title())) {
                sb.append(' ').append(source.title());
            }
            if (StringUtils.hasText(source.excerpt())) {
                sb.append(' ').append(source.excerpt());
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
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
