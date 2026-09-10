package ee.smit.aiagent.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseTest {

    @TempDir
    Path tempDir;

    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("gitlab-access.md"),
                """
                # GitLab ligipääs

                Taotle ligipääsu teenuste portaalis. SLA on 1–2 tööpäeva.
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("kubernetes-deploy.md"),
                """
                # Kubernetes deploy protsess

                Deploy käib Helm chartidega läbi CI/CD pipeline'i.
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("cicd-pipeline.md"),
                """
                # CI/CD pipeline

                Pipeline etapid: build, test, security, package, deploy.

                ## Hea tava

                Pinni toolide versioonid.
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("code-review.md"),
                """
                # Koodireview enne merge'i

                Merge request vajab vähemalt ühe approve'i enne merge'i.
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("git-workflow.md"),
                """
                # Git töövoog ja branching

                Kasuta feature harusid ja merge request'e main'i suunas.
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("notes.txt"), "ignore me", StandardCharsets.UTF_8);

        knowledgeBase = new KnowledgeBase(tempDir.toString(), 3);
        knowledgeBase.loadDocuments();
    }

    @Test
    void loadsAtLeastFiveMarkdownTopics() {
        List<KnowledgeDocument> docs = knowledgeBase.getDocuments();
        assertEquals(5, docs.size());
        Set<String> names = docs.stream().map(KnowledgeDocument::fileName).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "gitlab-access.md",
                "kubernetes-deploy.md",
                "cicd-pipeline.md",
                "code-review.md",
                "git-workflow.md"
        )));
        assertFalse(names.contains("notes.txt"));
    }

    @Test
    void listTopicsReturnsFileNameAndTitleWithoutFullContent() {
        List<KnowledgeDocument> topics = knowledgeBase.listTopics();
        assertEquals(5, topics.size());

        KnowledgeDocument gitlab = topics.stream()
                .filter(t -> t.fileName().equals("gitlab-access.md"))
                .findFirst()
                .orElseThrow();
        assertEquals("GitLab ligipääs", gitlab.title());
        assertEquals("", gitlab.content());
    }

    @Test
    void extractTitleFallsBackToFileNameWhenNoH1() {
        assertEquals("fallback.md", KnowledgeBase.extractTitle("no heading here", "fallback.md"));
        assertEquals("fallback.md", KnowledgeBase.extractTitle("", "fallback.md"));
        assertEquals("Pealkiri", KnowledgeBase.extractTitle("# Pealkiri\n\ntekst", "fallback.md"));
    }

    @Test
    void searchFindsGitlabDocument() {
        List<KnowledgeDocument> results = knowledgeBase.search("gitlab");
        assertFalse(results.isEmpty());
        assertEquals("gitlab-access.md", results.getFirst().fileName());
        assertTrue(results.getFirst().content().contains("teenuste portaalis"));
    }

    @Test
    void searchIsCaseInsensitive() {
        List<KnowledgeDocument> lower = knowledgeBase.search("kubernetes");
        List<KnowledgeDocument> upper = knowledgeBase.search("KUBERNETES");
        List<KnowledgeDocument> mixed = knowledgeBase.search("KubeRnetes");

        assertFalse(lower.isEmpty());
        assertEquals(lower, upper);
        assertEquals(lower, mixed);
        assertEquals("kubernetes-deploy.md", lower.getFirst().fileName());
    }

    @Test
    void searchFindsGitlabDespiteStopWordsAndShortQuery() {
        List<KnowledgeDocument> both = knowledgeBase.search("gitlab ligipääs");
        assertFalse(both.isEmpty());
        assertEquals("gitlab-access.md", both.getFirst().fileName());

        List<KnowledgeDocument> shortQuestion = knowledgeBase.search("gitlab ligipääs?");
        assertFalse(shortQuestion.isEmpty());
        assertEquals("gitlab-access.md", shortQuestion.getFirst().fileName());

        List<KnowledgeDocument> partial = knowledgeBase.search("gitlab mars");
        assertFalse(partial.isEmpty());
        assertEquals("gitlab-access.md", partial.getFirst().fileName());
    }

    @Test
    void searchRespectsTopK() {
        List<KnowledgeDocument> results = knowledgeBase.search("pipeline deploy merge");
        assertTrue(results.size() <= 3);

        KnowledgeBase topOne = new KnowledgeBase(tempDir.toString(), 1);
        topOne.loadDocuments();
        assertEquals(1, topOne.search("pipeline deploy merge git").size());
    }

    @Test
    void searchMatchesPluralStemTavad() {
        assertTrue(KnowledgeBase.containsTerm("hea tava", "tavad"));
        List<KnowledgeDocument> results = knowledgeBase.search("ci pipeline tavad");
        assertFalse(results.isEmpty());
        assertEquals("cicd-pipeline.md", results.getFirst().fileName());
    }

    @Test
    void searchReturnsEmptyForUnknownTopic() {
        assertTrue(knowledgeBase.search("somerandomsearchstring").isEmpty());
        assertTrue(knowledgeBase.search("").isEmpty());
        assertTrue(knowledgeBase.search("   ").isEmpty());
        assertTrue(knowledgeBase.search(null).isEmpty());
    }

    @Test
    void excerptTruncatesLongContent() {
        String longText = "a".repeat(600);
        String excerpt = KnowledgeBase.excerpt(longText, 500);
        assertTrue(excerpt.length() <= 501);
        assertTrue(excerpt.endsWith("…"));
    }

    @Test
    void getDocumentReturnsKnownFile() {
        Optional<KnowledgeDocument> doc = knowledgeBase.getDocument("gitlab-access.md");
        assertTrue(doc.isPresent());
        assertEquals("GitLab ligipääs", doc.get().title());
        assertTrue(doc.get().content().contains("1–2 tööpäeva"));
    }

    @Test
    void getDocumentReturnsEmptyForUnknownFile() {
        Optional<KnowledgeDocument> doc = knowledgeBase.getDocument("does-not-exist.md");
        assertTrue(doc.isEmpty());
    }

    @Test
    void getDocumentRejectsPathTraversal() {
        assertThrows(SecurityException.class,
                () -> knowledgeBase.getDocument("../etc/passwd"));
        assertThrows(SecurityException.class,
                () -> knowledgeBase.getDocument("../../etc/passwd"));
        assertThrows(SecurityException.class,
                () -> knowledgeBase.getDocument("..\\..\\windows\\system32"));
        assertThrows(SecurityException.class,
                () -> knowledgeBase.getDocument("subdir/secret.md"));
        assertThrows(SecurityException.class,
                () -> knowledgeBase.resolveSafePath("../../../etc/passwd"));
    }

    @Test
    void getDocumentRejectsBlankAndNonMarkdown() {
        assertThrows(IllegalArgumentException.class,
                () -> knowledgeBase.getDocument(""));
        assertThrows(IllegalArgumentException.class,
                () -> knowledgeBase.getDocument("   "));
        assertThrows(IllegalArgumentException.class,
                () -> knowledgeBase.getDocument(null));
        assertThrows(IllegalArgumentException.class,
                () -> knowledgeBase.getDocument("notes.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> knowledgeBase.getDocument("readme"));
    }

    @Test
    void resolveSafePathStaysInsideBaseDir() {
        Path resolved = knowledgeBase.resolveSafePath("gitlab-access.md");
        assertTrue(resolved.startsWith(knowledgeBase.getBaseDir()));
        assertEquals("gitlab-access.md", resolved.getFileName().toString());
    }

    @Test
    void missingDirectoryYieldsEmptyKnowledgeBase(@TempDir Path emptyParent) {
        Path missing = emptyParent.resolve("no-such-knowledge-dir");
        KnowledgeBase empty = new KnowledgeBase(missing.toString(), 3);
        empty.loadDocuments();

        assertTrue(empty.getDocuments().isEmpty());
        assertTrue(empty.listTopics().isEmpty());
        assertTrue(empty.search("gitlab").isEmpty());
    }
}
