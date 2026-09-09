package ee.smit.aiagent.knowledge;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final Path baseDir;
    private List<KnowledgeDocument> documents = List.of();

    public KnowledgeBase(@Value("${app.knowledge.path:knowledge}") String knowledgePath) {
        this.baseDir = Path.of(knowledgePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    void loadDocuments() {
        if (!Files.isDirectory(baseDir)) {
            log.warn("Knowledge base directory does not exist: {}", baseDir);
            documents = List.of();
            return;
        }

        try (Stream<Path> stream = Files.list(baseDir)) {
            documents = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::readDocument)
                    .toList();
            log.info("Loaded {} knowledge documents from {}", documents.size(), baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load knowledge base from " + baseDir, e);
        }
    }

    public List<KnowledgeDocument> listTopics() {
        return documents.stream()
                .map(doc -> new KnowledgeDocument(doc.fileName(), doc.title(), ""))
                .toList();
    }

    public List<KnowledgeDocument> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        return documents.stream()
                .filter(doc -> matchesAllTokens(doc, tokens))
                .toList();
    }

    public Optional<KnowledgeDocument> getDocument(String fileName) {
        Path safePath = resolveSafePath(fileName);

        return documents.stream()
                .filter(doc -> doc.fileName().equals(safePath.getFileName().toString()))
                .findFirst()
                .or(() -> readIfPresent(safePath));
    }

    Path resolveSafePath(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }

        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new SecurityException("Path traversal is not allowed: " + fileName);
        }

        if (!fileName.endsWith(".md")) {
            throw new IllegalArgumentException("Only markdown (.md) files are allowed: " + fileName);
        }

        Path resolved = baseDir.resolve(fileName).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("Path escapes knowledge base directory: " + fileName);
        }

        return resolved;
    }

    Path getBaseDir() {
        return baseDir;
    }

    List<KnowledgeDocument> getDocuments() {
        return documents;
    }

    private KnowledgeDocument readDocument(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String fileName = path.getFileName().toString();
            return new KnowledgeDocument(fileName, extractTitle(content, fileName), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read knowledge file: " + path, e);
        }
    }

    private Optional<KnowledgeDocument> readIfPresent(Path safePath) {
        if (!Files.isRegularFile(safePath)) {
            return Optional.empty();
        }
        return Optional.of(readDocument(safePath));
    }

    static String extractTitle(String content, String fileNameFallback) {
        if (content == null || content.isBlank()) {
            return fileNameFallback;
        }
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).trim();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        }
        return fileNameFallback;
    }

    private static List<String> tokenize(String query) {
        String[] parts = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static boolean matchesAllTokens(KnowledgeDocument doc, List<String> tokens) {
        String haystack = (doc.title() + "\n" + doc.content()).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }
}
