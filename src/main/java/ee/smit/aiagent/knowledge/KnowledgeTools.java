package ee.smit.aiagent.knowledge;

import ee.smit.aiagent.model.KnowledgeDocument;
import ee.smit.aiagent.model.SourceDto;
import ee.smit.aiagent.security.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);
    private static final int MAX_SEARCH_EXCERPT_LENGTH = 200;

    private static final String BLOCKED_SENSITIVE =
            "Document blocked: sensitive content detected";

    private final KnowledgeBase knowledgeBase;
    private final ToolSourcesBuffer sourcesBuffer;
    private final SensitiveDataRedactor sensitiveDataRedactor;

    @Autowired
    public KnowledgeTools(
            KnowledgeBase knowledgeBase,
            ToolSourcesBuffer sourcesBuffer,
            SensitiveDataRedactor sensitiveDataRedactor) {
        this.knowledgeBase = knowledgeBase;
        this.sourcesBuffer = sourcesBuffer;
        this.sensitiveDataRedactor = sensitiveDataRedactor;
    }

    public KnowledgeTools(KnowledgeBase knowledgeBase, ToolSourcesBuffer sourcesBuffer) {
        this(knowledgeBase, sourcesBuffer, new SensitiveDataRedactor());
    }

    @Tool(description = "List available knowledge base topics. Returns file name and title for each document. Use for overview questions like which topics are available.")
    public List<Map<String, String>> list_topics() {
        try {
            List<Map<String, String>> topics = new ArrayList<>();
            for (KnowledgeDocument doc : knowledgeBase.listTopics()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("file", doc.fileName());
                item.put("title", doc.title());
                topics.add(item);
                String excerpt = doc.title() != null && !doc.title().isBlank()
                        ? doc.title()
                        : doc.fileName();
                sourcesBuffer.add(new SourceDto(doc.fileName(), doc.title(), excerpt));
            }
            return topics;
        } catch (Exception e) {
            log.warn("list_topics failed: {}", e.getMessage());
            return List.of(Map.of("error", "Failed to list topics"));
        }
    }

    @Tool(description = """
            Search the knowledge base with SHORT keywords (e.g. "CI pipeline tava", "GitLab ligipääs"), \
            not the full user sentence. Returns candidate files with a short preview. \
            This does NOT count as a source — after choosing a file, call get_document to read it fully.""")
    public List<Map<String, String>> search_knowledge(
            @ToolParam(description = "Short search keywords in the knowledge base language") String query) {
        try {
            if (query == null || query.isBlank()) {
                return List.of();
            }

            List<KnowledgeDocument> hits = knowledgeBase.search(query);
            List<Map<String, String>> results = new ArrayList<>();

            for (KnowledgeDocument doc : hits) {
                String content = doc.content() == null ? "" : doc.content();
                if (sensitiveDataRedactor.containsSecret(content)) {
                    log.warn("search_knowledge omitted file with secret-like content: {}", doc.fileName());
                    continue;
                }
                String safePreview = sensitiveDataRedactor.maskPii(
                        KnowledgeBase.excerpt(content, MAX_SEARCH_EXCERPT_LENGTH));
                Map<String, String> item = new LinkedHashMap<>();
                item.put("file", doc.fileName());
                item.put("title", doc.title());
                item.put("preview", safePreview);
                results.add(item);
            }

            return results;
        } catch (Exception e) {
            log.warn("search_knowledge failed: {}", e.getMessage());
            return List.of(Map.of("error", "Search failed"));
        }
    }

    @Tool(description = """
            Read the full content of one knowledge base markdown file by exact file name \
            (e.g. "cicd-pipeline.md"). Use after search_knowledge or list_topics. \
            Only files read with this tool are treated as answer sources.""")
    public Map<String, String> get_document(
            @ToolParam(description = "Exact markdown file name, e.g. cicd-pipeline.md") String file) {
        try {
            if (file == null || file.isBlank()) {
                return Map.of("error", "file must not be blank");
            }

            Optional<KnowledgeDocument> found = knowledgeBase.getDocument(file.trim());
            if (found.isEmpty()) {
                return Map.of("error", "Document not found: " + file);
            }

            KnowledgeDocument doc = found.get();
            String content = doc.content() == null ? "" : doc.content();
            if (sensitiveDataRedactor.containsSecret(content)) {
                log.warn("get_document blocked sensitive file: {}", doc.fileName());
                return Map.of("error", BLOCKED_SENSITIVE);
            }

            String safeContent = sensitiveDataRedactor.maskPii(content);
            String excerpt = KnowledgeBase.excerpt(safeContent, KnowledgeBase.DEFAULT_EXCERPT_LENGTH);

            sourcesBuffer.add(new SourceDto(doc.fileName(), doc.title(), excerpt));

            Map<String, String> result = new LinkedHashMap<>();
            result.put("file", doc.fileName());
            result.put("title", doc.title());
            result.put("content", safeContent);
            return result;
        } catch (SecurityException | IllegalArgumentException e) {
            log.warn("get_document rejected '{}': {}", file, e.getMessage());
            return Map.of("error", e.getMessage());
        } catch (Exception e) {
            log.warn("get_document failed for '{}': {}", file, e.getMessage());
            return Map.of("error", "Failed to read document");
        }
    }
}
