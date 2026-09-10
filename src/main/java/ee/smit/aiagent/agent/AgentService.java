package ee.smit.aiagent.agent;

import ee.smit.aiagent.knowledge.ToolSourcesBuffer;
import ee.smit.aiagent.model.AgentLlmResponse;
import ee.smit.aiagent.model.AskRequest;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.model.SourceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String SOURCE_CITATION_MARKER = "[allikas:";

    private final ChatClient chatClient;
    private final ToolSourcesBuffer sourcesBuffer;
    private final String openAiApiKey;

    public AgentService(
            ChatClient chatClient,
            ToolSourcesBuffer sourcesBuffer,
            @Value("${spring.ai.openai.api-key:}") String openAiApiKey) {
        this.chatClient = chatClient;
        this.sourcesBuffer = sourcesBuffer;
        this.openAiApiKey = openAiApiKey;
    }

    public AskResponse ask(AskRequest request) {
        ensureApiKeyConfigured();
        sourcesBuffer.clear();

        AgentLlmResponse llmResponse;
        try {
            llmResponse = chatClient.prompt()
                    .user(request.question())
                    .call()
                    .entity(AgentLlmResponse.class);
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
