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
            boolean incomingHasExcerpt = incoming.excerpt() != null && !incoming.excerpt().isBlank();
            boolean existingHasExcerpt = existing.excerpt() != null && !existing.excerpt().isBlank();
            if (incomingHasExcerpt && !existingHasExcerpt) {
                return incoming;
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
}
