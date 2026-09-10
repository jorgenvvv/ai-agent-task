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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final double TITLE_BOOST = 3.0;
    private static final double FILE_NAME_BOOST = 2.0;
    private static final double CONTENT_TERM_WEIGHT = 1.0;
    public static final int DEFAULT_EXCERPT_LENGTH = 500;

    private static final Set<String> STOP_WORDS = Set.of(
            "ja", "ning", "voi", "või", "on", "ei", "mis", "kuidas", "kas", "mul", "saan",
            "ole", "see", "need", "kui", "ka", "et", "me", "te", "oma", "seda", "selle",
            "nende", "oli", "oleks", "saab", "peab", "aga", "siis", "seal", "siin",
            "milline", "millal", "miks", "kust", "kuhu", "kes", "kelle", "hea", "head",

            "the", "a", "an", "to", "for", "of", "in", "is", "are", "was", "were",
            "be", "been", "and", "or", "not", "with", "from", "by", "as", "at", "it",
            "this", "that", "how", "what", "when", "where", "which", "who", "can", "do"
    );

    private final Path baseDir;
    private final int topK;
    private List<KnowledgeDocument> documents = List.of();

    public KnowledgeBase(
            @Value("${app.knowledge.path:knowledge}") String knowledgePath,
            @Value("${app.knowledge.search.top-k:3}") int topK) {
        this.baseDir = Path.of(knowledgePath).toAbsolutePath().normalize();
        this.topK = Math.max(1, topK);
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

        record Scored(KnowledgeDocument doc, double score) {}

        return documents.stream()
                .map(doc -> new Scored(doc, scoreDocument(doc, tokens)))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(s -> s.doc().fileName()))
                .limit(topK)
                .map(Scored::doc)
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

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT).trim();
        String[] parts = TOKEN_SPLIT.split(lower);
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part.length() >= MIN_TOKEN_LENGTH && !STOP_WORDS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static double scoreDocument(KnowledgeDocument doc, List<String> queryTokens) {
        String titleLower = doc.title() == null ? "" : doc.title().toLowerCase(Locale.ROOT);
        String fileLower = doc.fileName() == null ? "" : doc.fileName().toLowerCase(Locale.ROOT);
        String contentLower = doc.content() == null ? "" : doc.content().toLowerCase(Locale.ROOT);

        double score = 0;
        for (String term : queryTokens) {
            if (containsTerm(titleLower, term)) {
                score += TITLE_BOOST;
            }
            if (containsTerm(fileLower, term)) {
                score += FILE_NAME_BOOST;
            }
            if (containsTerm(contentLower, term)) {
                score += CONTENT_TERM_WEIGHT;
            }
        }
        return score;
    }

    static boolean containsTerm(String haystack, String term) {
        if (haystack == null || haystack.isEmpty() || term == null || term.isEmpty()) {
            return false;
        }
        if (haystack.contains(term)) {
            return true;
        }
        if (term.length() > 3 && term.endsWith("d")) {
            String stem = term.substring(0, term.length() - 1);
            if (stem.length() >= MIN_TOKEN_LENGTH && haystack.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    public static String excerpt(String content, int maxLen) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.strip();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen).stripTrailing() + "…";
    }
}
