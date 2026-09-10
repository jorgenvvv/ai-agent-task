package ee.smit.aiagent.knowledge;

import ee.smit.aiagent.model.SourceDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSourcesBufferTest {

    @Test
    void separateInstancesDoNotShareSources() {
        ToolSourcesBuffer a = new ToolSourcesBuffer();
        ToolSourcesBuffer b = new ToolSourcesBuffer();

        a.add(new SourceDto("a.md", "A", "excerpt a"));
        b.add(new SourceDto("b.md", "B", "excerpt b"));

        List<SourceDto> snapA = a.snapshot();
        List<SourceDto> snapB = b.snapshot();
        assertEquals(1, snapA.size());
        assertEquals("a.md", snapA.getFirst().file());
        assertEquals(1, snapB.size());
        assertEquals("b.md", snapB.getFirst().file());
    }

    @Test
    void clearRemovesCurrentTurnSources() {
        ToolSourcesBuffer buffer = new ToolSourcesBuffer();
        buffer.add(new SourceDto("x.md", "X", "ex"));
        buffer.clear();
        assertTrue(buffer.snapshot().isEmpty());
    }
}
