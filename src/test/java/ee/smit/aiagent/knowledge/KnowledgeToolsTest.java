package ee.smit.aiagent.knowledge;

import ee.smit.aiagent.model.SourceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeToolsTest {

    @TempDir
    Path tempDir;

    private KnowledgeTools tools;
    private ToolSourcesBuffer sourcesBuffer;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("cicd-pipeline.md"),
                """
                # CI/CD pipeline

                Pipeline etapid: build, test.

                ## Hea tava

                Pinni toolide versioonid.
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("gitlab-access.md"),
                """
                # GitLab ligipääs

                Taotle ligipääsu teenuste portaalis.
                """, StandardCharsets.UTF_8);

        KnowledgeBase knowledgeBase = new KnowledgeBase(tempDir.toString(), 3);
        knowledgeBase.loadDocuments();
        sourcesBuffer = new ToolSourcesBuffer();
        tools = new KnowledgeTools(knowledgeBase, sourcesBuffer);
    }

    @Test
    void searchKnowledgeDoesNotRegisterSources() {
        List<Map<String, String>> hits = tools.search_knowledge("ci pipeline");
        assertFalse(hits.isEmpty());
        assertEquals("cicd-pipeline.md", hits.getFirst().get("file"));
        assertTrue(hits.getFirst().containsKey("preview"));
        assertTrue(sourcesBuffer.snapshot().isEmpty());
    }

    @Test
    void getDocumentRegistersSourceWithFileAndNonBlankExcerpt() {
        Map<String, String> doc = tools.get_document("cicd-pipeline.md");
        assertEquals("cicd-pipeline.md", doc.get("file"));
        assertEquals("CI/CD pipeline", doc.get("title"));
        assertTrue(doc.get("content").contains("Hea tava"));

        List<SourceDto> sources = sourcesBuffer.snapshot();
        assertEquals(1, sources.size());
        assertEquals("cicd-pipeline.md", sources.getFirst().file());
        assertEquals("CI/CD pipeline", sources.getFirst().title());
        assertFalse(sources.getFirst().excerpt().isBlank());
    }

    @Test
    void getDocumentMissingFileReturnsErrorWithoutSource() {
        Map<String, String> result = tools.get_document("missing.md");
        assertTrue(result.containsKey("error"));
        assertTrue(sourcesBuffer.snapshot().isEmpty());
    }

    @Test
    void getDocumentRejectsPathTraversalWithoutSource() {
        Map<String, String> result = tools.get_document("../etc/passwd");
        assertTrue(result.containsKey("error"));
        assertTrue(sourcesBuffer.snapshot().isEmpty());
    }

    @Test
    void listTopicsReturnsFilesAndRegistersTopicSources() {
        List<Map<String, String>> topics = tools.list_topics();
        assertEquals(2, topics.size());
        assertEquals(2, sourcesBuffer.snapshot().size());
        assertTrue(sourcesBuffer.snapshot().stream().allMatch(s ->
                s.file() != null && s.excerpt() != null && !s.excerpt().isBlank()));
    }

    @Test
    void onlyThreeKnowledgeToolsAreExposed() {
        Set<String> toolNames = Arrays.stream(KnowledgeTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("list_topics", "search_knowledge", "get_document"), toolNames);
    }

    @Test
    void getDocumentBlocksSecretContentWithoutRegisteringSource(@TempDir Path secretDir) throws IOException {
        Files.writeString(secretDir.resolve("secret-doc.md"),
                """
                # Secret doc

                password=FAKE_KB_SECRET_782
                """, StandardCharsets.UTF_8);

        Path clean = secretDir.resolve("clean-kb");
        Files.createDirectories(clean);
        Files.writeString(clean.resolve("ok.md"), "# OK\n\nSafe text.\n", StandardCharsets.UTF_8);
        KnowledgeBase kb = new KnowledgeBase(clean.toString(), 3);
        kb.loadDocuments();
        Files.writeString(clean.resolve("leaky.md"),
                "# Leaky\n\npassword=FAKE_KB_SECRET_782\n", StandardCharsets.UTF_8);

        ToolSourcesBuffer buffer = new ToolSourcesBuffer();
        KnowledgeTools localTools = new KnowledgeTools(kb, buffer);

        Map<String, String> result = localTools.get_document("leaky.md");
        assertTrue(result.containsKey("error"));
        assertFalse(result.getOrDefault("error", "").contains("FAKE_KB_SECRET_782"));
        assertFalse(result.containsKey("content"));
        assertTrue(buffer.snapshot().isEmpty());
    }

    @Test
    void searchOmitsSecretDocumentsFromPreview(@TempDir Path secretDir) throws IOException {
        Path dir = secretDir.resolve("kb");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("safe.md"), "# Safe\n\nPipeline info.\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("bad.md"),
                "# Bad\n\npassword=FAKE_KB_SECRET_782 and pipeline.\n", StandardCharsets.UTF_8);
        KnowledgeBase kb = new KnowledgeBase(dir.toString(), 5);
        kb.loadDocuments();
        assertEquals(1, kb.getDocuments().size());

        KnowledgeTools localTools = new KnowledgeTools(kb, new ToolSourcesBuffer());
        List<Map<String, String>> hits = localTools.search_knowledge("pipeline");
        assertTrue(hits.stream().noneMatch(h ->
                String.valueOf(h).contains("FAKE_KB_SECRET_782")));
        assertTrue(hits.stream().noneMatch(h -> "bad.md".equals(h.get("file"))));
    }
}
