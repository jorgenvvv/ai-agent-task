package ee.smit.aiagent.agent;

import ee.smit.aiagent.model.AgentLlmResponse;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.model.SourceDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServicePostRulesTest {

    @Test
    void noSourcesForcesRefused() {
        AgentLlmResponse llm = new AgentLlmResponse(
                "Siin on vastus ilma allikata.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, List.of());

        assertTrue(response.refused());
        assertEquals("low", response.confidence());
        assertTrue(response.sources().isEmpty());
        assertEquals("Teadmusbaasist ei leitud allikaid", response.refusalReason());
        assertFalse(response.answer().isBlank());
    }

    @Test
    void refusedWithEmptySourcesIsOk() {
        AgentLlmResponse llm = new AgentLlmResponse(
                "See teema on skoobist väljas.",
                true,
                "Skoobist väljas",
                "high");

        AskResponse response = AgentService.applyPostRules(llm, List.of());

        assertTrue(response.refused());
        assertEquals("low", response.confidence());
        assertTrue(response.sources().isEmpty());
        assertEquals("Skoobist väljas", response.refusalReason());
    }

    @Test
    void sourcesPresentKeepsRefusedFalse() {
        List<SourceDto> sources = List.of(
                new SourceDto("gitlab-access.md", "GitLab ligipääs", "Taotle ligipääsu teenuste portaalis."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. [allikas: gitlab-access.md]",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused());
        assertEquals("high", response.confidence());
        assertEquals(1, response.sources().size());
        assertEquals("gitlab-access.md", response.sources().getFirst().file());
    }

    @Test
    void missingCitationAppendsSourceSuffix() {
        List<SourceDto> sources = List.of(
                new SourceDto("gitlab-access.md", "GitLab ligipääs", "Taotle ligipääsu teenuste portaalis."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused());
        assertTrue(response.answer().contains("[allikas: gitlab-access.md]"));
        assertTrue(response.answer().startsWith("Taotle ligipääsu teenuste portaalis."));
    }

    @Test
    void doesNotDuplicateCitationWhenAlreadyPresent() {
        List<SourceDto> sources = List.of(
                new SourceDto("cicd-pipeline.md", "CI/CD", "Pipeline etapid."));
        AgentLlmResponse withMarker = new AgentLlmResponse(
                "Pinni versioonid. [allikas: cicd-pipeline.md]",
                false,
                null,
                "high");
        AgentLlmResponse withFileName = new AgentLlmResponse(
                "Vt cicd-pipeline.md heade tavade kohta.",
                false,
                null,
                "high");

        AskResponse marked = AgentService.applyPostRules(withMarker, sources);
        AskResponse named = AgentService.applyPostRules(withFileName, sources);

        assertEquals(1, countOccurrences(marked.answer(), "[allikas:"));
        assertEquals("Vt cicd-pipeline.md heade tavade kohta.", named.answer());
    }

    @Test
    void sourcesMustHaveFileAndNonBlankExcerpt() {
        List<SourceDto> mixed = List.of(
                new SourceDto("ok.md", "OK", "Sisu excerpt"),
                new SourceDto("blank-excerpt.md", "Blank", "  "),
                new SourceDto("", "No file", "excerpt"),
                new SourceDto(null, "Null file", "excerpt"),
                new SourceDto("no-excerpt.md", "No excerpt", null));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Vastus olemas.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, mixed);

        assertFalse(response.refused());
        assertEquals(1, response.sources().size());
        assertEquals("ok.md", response.sources().getFirst().file());
        assertFalse(response.sources().getFirst().excerpt().isBlank());
        assertTrue(response.answer().contains("[allikas: ok.md]"));
    }

    @Test
    void onlyInvalidSourcesForcesRefused() {
        List<SourceDto> invalid = List.of(
                new SourceDto("x.md", "X", ""),
                new SourceDto("", "Y", "excerpt"));
        AgentLlmResponse llm = new AgentLlmResponse("Vastus", false, null, "high");

        AskResponse response = AgentService.applyPostRules(llm, invalid);

        assertTrue(response.refused());
        assertTrue(response.sources().isEmpty());
        assertEquals("low", response.confidence());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
