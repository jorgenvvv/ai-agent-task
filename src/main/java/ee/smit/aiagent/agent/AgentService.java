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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String SOURCE_CITATION_MARKER = "[allikas:";
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,100}$");

    private static final String DEFAULT_REFUSAL_ANSWER =
            "Kahjuks ei saa ma selle päringuga jätkata. Palun esita tavaline küsimus IT teenuste teadmusbaasi kohta.";
    private static final String REFUSAL_REASON_GUARD = "Kahtlane või lubamatu sisend";
    private static final String REFUSAL_REASON_SENSITIVE = "Päring sisaldab tundlikke andmeid";

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
            log.error("OpenAI / ChatClient call failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider request failed: " + rootMessage(e),
                    e);
        }

        if (llmResponse == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI provider returned an empty response");
        }

        List<SourceDto> sources = sourcesBuffer.snapshot();
        return applyPostRules(llmResponse, sources);
    }

    private void logRejected(GuardReasonCode reasonCode, String sessionHash, int length) {
        log.warn("prompt_injection_rejected sessionHash={} reasonCode={} length={}",
                sessionHash, reasonCode, length);
    }

    static AskResponse refusedResponse(GuardReasonCode reasonCode) {
        String reason = reasonCode == GuardReasonCode.SENSITIVE_DATA
                ? REFUSAL_REASON_SENSITIVE
                : REFUSAL_REASON_GUARD;
        return new AskResponse(DEFAULT_REFUSAL_ANSWER, List.of(), "low", true, reason);
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
        String refusalReason = llm.refusalReason();
        String confidence = normalizeConfidence(llm.confidence(), refused);

        if (!refused && sources.isEmpty()) {
            refused = true;
            confidence = "low";
            sources = List.of();
            if (!StringUtils.hasText(refusalReason)) {
                refusalReason = "Teadmusbaasist ei leitud allikaid";
            }
            if (!StringUtils.hasText(answer)) {
                answer = "Kahjuks ei leitud teadmusbaasist selle küsimuse jaoks allikaid.";
            }
        }

        if (refused) {
            confidence = "low";
        } else {
            if (!StringUtils.hasText(confidence)) {
                confidence = "high";
            }
            answer = ensureSourceCitation(answer, sources);
        }

        return new AskResponse(answer, sources, confidence, refused, refusalReason);
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

    private static String ensureSourceCitation(String answer, List<SourceDto> sources) {
        if (sources.isEmpty()) {
            return answer;
        }
        String text = answer == null ? "" : answer;
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
        if (!StringUtils.hasText(text)) {
            return suffix.stripLeading();
        }
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
