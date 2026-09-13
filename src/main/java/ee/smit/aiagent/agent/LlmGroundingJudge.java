package ee.smit.aiagent.agent;

import ee.smit.aiagent.model.GroundingJudgeResponse;
import ee.smit.aiagent.model.SourceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class LlmGroundingJudge implements GroundingJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmGroundingJudge.class);

    private final ChatClient groundingChatClient;
    private final int maxCorpusChars;
    private final int maxAnswerChars;

    public LlmGroundingJudge(
            @Qualifier("groundingChatClient") ChatClient groundingChatClient,
            @Value("${app.agent.grounding.judge-max-corpus-chars:6000}") int maxCorpusChars,
            @Value("${app.agent.grounding.judge-max-answer-chars:4000}") int maxAnswerChars) {
        this.groundingChatClient = groundingChatClient;
        this.maxCorpusChars = Math.max(500, maxCorpusChars);
        this.maxAnswerChars = Math.max(200, maxAnswerChars);
    }

    @Override
    public boolean isGrounded(String answer, List<SourceDto> sources) {
        if (!StringUtils.hasText(answer) || sources == null || sources.isEmpty()) {
            return false;
        }
        String corpus = buildCorpus(sources);
        if (!StringUtils.hasText(corpus)) {
            return false;
        }

        String safeAnswer = truncate(answer.strip(), maxAnswerChars);
        String safeCorpus = truncate(corpus, maxCorpusChars);
        String userPayload = """
                CONTEXT:
                ---
                %s
                ---

                ANSWER:
                ---
                %s
                ---
                """.formatted(safeCorpus, safeAnswer);

        try {
            GroundingJudgeResponse result = groundingChatClient.prompt()
                    .user(userPayload)
                    .call()
                    .entity(GroundingJudgeResponse.class);
            if (result == null) {
                log.warn("grounding_judge empty_response");
                return false;
            }
            String reason = sanitizeLogText(result.reason(), 300);
            if (!result.grounded()) {
                log.info("grounding_judge rejected answerLen={} sources={} reason={}",
                        safeAnswer.length(),
                        sourceFilesForLog(sources),
                        reason.isEmpty() ? "-" : reason);
                if (log.isDebugEnabled()) {
                    log.debug("grounding_judge rejected answerSnippet={} corpusSnippet={}",
                            sanitizeLogText(safeAnswer, 240),
                            sanitizeLogText(safeCorpus, 240));
                }
            } else if (log.isDebugEnabled()) {
                log.debug("grounding_judge accepted answerLen={} sources={} reason={}",
                        safeAnswer.length(),
                        sourceFilesForLog(sources),
                        reason.isEmpty() ? "-" : reason);
            }
            return result.grounded();
        } catch (Exception e) {
            log.warn("grounding_judge failed failClosed=true detail={}", rootMessage(e));
            return false;
        }
    }

    static String buildCorpus(List<SourceDto> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SourceDto source : sources) {
            if (source == null) {
                continue;
            }
            if (StringUtils.hasText(source.file())) {
                sb.append("file: ").append(source.file().strip()).append('\n');
            }
            if (StringUtils.hasText(source.title())) {
                sb.append("title: ").append(source.title().strip()).append('\n');
            }
            if (StringUtils.hasText(source.excerpt())) {
                sb.append(source.excerpt().strip()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().strip().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    private static String sourceFilesForLog(List<SourceDto> sources) {
        if (sources == null || sources.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (SourceDto source : sources) {
            if (source == null || !StringUtils.hasText(source.file())) {
                continue;
            }
            if (n > 0) {
                sb.append(',');
            }
            sb.append(source.file().strip());
            n++;
            if (n >= 5) {
                sb.append(",…");
                break;
            }
        }
        return n == 0 ? "-" : sb.toString();
    }

    static String sanitizeLogText(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String oneLine = text.strip().replaceAll("\s+", " ");
        if (oneLine.length() <= maxChars) {
            return oneLine;
        }
        return oneLine.substring(0, maxChars) + "…";
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
