package ee.smit.aiagent.knowledge;

import ee.smit.aiagent.model.SourceDto;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ToolSourcesBuffer {

    private final Map<String, SourceDto> sourcesByFile = new LinkedHashMap<>();

    public void add(SourceDto source) {
        if (source == null || source.file() == null || source.file().isBlank()) {
            return;
        }
        sourcesByFile.merge(source.file(), source, (existing, incoming) -> {
            int incomingLen = excerptLength(incoming);
            int existingLen = excerptLength(existing);
            if (incomingLen > existingLen) {
                return incoming;
            }
            if (incomingLen == existingLen && incomingLen > 0) {
                String incomingTitle = incoming.title() != null ? incoming.title() : "";
                String existingTitle = existing.title() != null ? existing.title() : "";
                if (incomingTitle.length() > existingTitle.length()) {
                    return incoming;
                }
            }
            return existing;
        });
    }

    public void addAll(List<SourceDto> sources) {
        if (sources == null) {
            return;
        }
        for (SourceDto source : sources) {
            add(source);
        }
    }

    public List<SourceDto> snapshot() {
        return List.copyOf(new ArrayList<>(sourcesByFile.values()));
    }

    public void clear() {
        sourcesByFile.clear();
    }

    private static int excerptLength(SourceDto source) {
        if (source == null || source.excerpt() == null) {
            return 0;
        }
        String excerpt = source.excerpt().strip();
        return excerpt.isEmpty() ? 0 : excerpt.length();
    }
}
