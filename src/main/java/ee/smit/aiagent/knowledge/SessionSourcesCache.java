package ee.smit.aiagent.knowledge;

import ee.smit.aiagent.model.SourceDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionSourcesCache {

    private final ConcurrentHashMap<String, List<SourceDto>> bySession = new ConcurrentHashMap<>();

    public void put(String sessionKey, List<SourceDto> sources) {
        if (sessionKey == null || sessionKey.isBlank() || sources == null || sources.isEmpty()) {
            return;
        }
        List<SourceDto> copy = List.copyOf(new ArrayList<>(sources));
        bySession.put(sessionKey, copy);
    }

    public List<SourceDto> get(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return List.of();
        }
        List<SourceDto> cached = bySession.get(sessionKey);
        return cached == null ? List.of() : cached;
    }

    public void clear(String sessionKey) {
        if (sessionKey != null) {
            bySession.remove(sessionKey);
        }
    }
}
