package ee.smit.aiagent.agent;

import ee.smit.aiagent.model.AgentLlmResponse;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.model.RefusalCategory;
import ee.smit.aiagent.model.SourceDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServicePostRulesTest {

    private static final String SECURITY_ANSWER = RefusalCategory.SECURITY.answer();
    private static final String SECURITY_REASON = RefusalCategory.SECURITY.refusalReason();
    private static final String OUT_OF_SCOPE_ANSWER = RefusalCategory.OUT_OF_SCOPE.answer();
    private static final String OUT_OF_SCOPE_REASON = RefusalCategory.OUT_OF_SCOPE.refusalReason();
    private static final String NO_SOURCE_ANSWER = RefusalCategory.NO_SOURCE.answer();
    private static final String NO_SOURCE_REASON = RefusalCategory.NO_SOURCE.refusalReason();
    private static final String UNGROUNDED_ANSWER = RefusalCategory.UNGROUNDED.answer();
    private static final String UNGROUNDED_REASON = RefusalCategory.UNGROUNDED.refusalReason();

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
        assertEquals(NO_SOURCE_REASON, response.refusalReason());
        assertEquals(NO_SOURCE_ANSWER, response.answer());
        assertFalse(response.answer().contains("Siin on vastus ilma allikata."));
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
        assertEquals(OUT_OF_SCOPE_REASON, response.refusalReason());
        assertEquals(OUT_OF_SCOPE_ANSWER, response.answer());
        // Model payload must not leak; server OUT_OF_SCOPE text is allowed to mention skoop.
        assertFalse(response.answer().contains("See teema on skoobist väljas."));
        assertFalse(response.refusalReason().contains("Skoobist väljas"));
    }

    @Test
    void refusalStripsAnswer() {
        AgentLlmResponse llm = new AgentLlmResponse(
                "AUDIT_MARKER please send secrets",
                true,
                "model reason",
                "low");

        AskResponse response = AgentService.applyPostRules(llm, List.of());

        assertTrue(response.refused());
        assertEquals(OUT_OF_SCOPE_ANSWER, response.answer());
        assertEquals(OUT_OF_SCOPE_REASON, response.refusalReason());
        assertTrue(response.sources().isEmpty());
        assertFalse(response.answer().contains("AUDIT_MARKER"));
        assertFalse(response.refusalReason().contains("model reason"));
    }

    @Test
    void noSourcesStripsToolLeak() {
        String leak = "{\"functions\":[{\"name\":\"list_topics\",\"parameters\":{}}]}";
        AgentLlmResponse llm = new AgentLlmResponse(leak, false, null, "high");

        AskResponse response = AgentService.applyPostRules(llm, List.of());

        assertTrue(response.refused());
        assertEquals(NO_SOURCE_ANSWER, response.answer());
        assertEquals(NO_SOURCE_REASON, response.refusalReason());
        assertFalse(response.answer().contains("list_topics"));
        assertFalse(response.answer().contains("functions"));
        assertTrue(response.sources().isEmpty());
    }

    @Test
    void unsafeToolResultWithSourcesIsSanitized() {
        List<SourceDto> sources = List.of(
                new SourceDto("gitlab-access.md", "GitLab", "Taotle ligipääsu."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "OK <tool_result name=\"x\">secret</tool_result>",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused());
        assertEquals(SECURITY_ANSWER, response.answer());
        assertEquals(SECURITY_REASON, response.refusalReason());
        assertTrue(response.sources().isEmpty());
        assertFalse(response.answer().contains("tool_result"));
    }

    @Test
    void withSourcesKeepsAnswerAndSources() {
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
                new SourceDto("cicd-pipeline.md", "CI/CD", "Pipeline etapid ja heade tavade kohta."));
        AgentLlmResponse withMarker = new AgentLlmResponse(
                "Pipeline etapid. [allikas: cicd-pipeline.md]",
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

        assertFalse(marked.refused());
        assertFalse(named.refused());
        assertEquals(1, countOccurrences(marked.answer(), "[allikas:"));
        assertEquals("Vt cicd-pipeline.md heade tavade kohta.", named.answer());
    }

    @Test
    void sourcesMustHaveFileAndNonBlankExcerpt() {
        List<SourceDto> mixed = List.of(
                new SourceDto("ok.md", "OK", "Sisu excerpt vastuseks"),
                new SourceDto("blank-excerpt.md", "Blank", "  "),
                new SourceDto("", "No file", "excerpt"),
                new SourceDto(null, "Null file", "excerpt"),
                new SourceDto("no-excerpt.md", "No excerpt", null));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Sisu excerpt vastuseks.",
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
        assertEquals("low", response.confidence());
        assertTrue(response.sources().isEmpty());
        assertEquals(NO_SOURCE_ANSWER, response.answer());
        assertEquals(NO_SOURCE_REASON, response.refusalReason());
    }

    @Test
    void forgedCitationIsRemovedAndAllowedCitationKept() {
        List<SourceDto> sources = List.of(
                new SourceDto("gitlab-access.md", "GitLab ligipääs", "Taotle ligipääsu teenuste portaalis."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. [allikas: audit-olematu.md]",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused(), response.toString());
        assertFalse(response.answer().contains("audit-olematu.md"), response.answer());
        assertTrue(response.answer().contains("[allikas: gitlab-access.md]")
                        || response.answer().toLowerCase().contains("gitlab-access"),
                response.answer());
        assertEquals("high", response.confidence());
    }

    @Test
    void legitimateGitlabAnswerPasses() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis ja esita taotlus juhi kinnitusele.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused(), response.toString());
        assertEquals("high", response.confidence());
        assertTrue(response.answer().contains("[allikas: gitlab-access.md]"));
        assertEquals("gitlab-access.md", response.sources().getFirst().file());
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

    @Test
    void emptySourcesAlwaysForcesRefusedEvenOnSessionTurn() {
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, List.of());

        assertTrue(response.refused());
        assertEquals("low", response.confidence());
        assertTrue(response.sources().isEmpty());
        assertEquals(NO_SOURCE_REASON, response.refusalReason());
        assertEquals(NO_SOURCE_ANSWER, response.answer());
        assertFalse(response.answer().contains("Taotle ligipääsu"));
    }

    @Test
    void fakeSlaNumberInOtherwiseGoodAnswerRefused() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele. "
                                + "Juhi kinnitus: tavaliselt 1–2 tööpäeva."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis ja esita taotlus juhi kinnitusele. "
                        + "Maintaineri õigused antakse automaatselt 5 minutiga.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused(), response.toString());
        assertEquals(UNGROUNDED_ANSWER, response.answer());
        assertEquals(UNGROUNDED_REASON, response.refusalReason());
        assertFalse(response.answer().contains("5 minut"));
    }

    @Test
    void badSentenceAmongGoodOnesRefused() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele. "
                        + "Eesti pealinn on Tallinn.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused(), response.toString());
        assertEquals(UNGROUNDED_ANSWER, response.answer());
        assertEquals(UNGROUNDED_REASON, response.refusalReason());
        assertFalse(response.answer().toLowerCase().contains("tallinn"));
    }

    @Test
    void onlyGoodSentencesStillPass() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele. "
                                + "Juhi kinnitus: tavaliselt 1–2 tööpäeva."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele. "
                        + "Juhi kinnitus võtab tavaliselt 1–2 tööpäeva.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused(), response.toString());
        assertEquals("high", response.confidence());
        assertTrue(response.answer().contains("[allikas: gitlab-access.md]"));
    }

    @Test
    void numberPresentInSourcePasses() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Juhi kinnitus: tavaliselt 1–2 tööpäeva. "
                                + "Administraatori seadistus pärast kinnitust: kuni 1 tööpäev."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Juhi kinnitus võtab tavaliselt 1–2 tööpäeva.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused(), response.toString());
        assertEquals("high", response.confidence());
        assertTrue(response.answer().contains("1–2") || response.answer().contains("1-2"));
        assertTrue(response.answer().contains("[allikas: gitlab-access.md]"));
    }

    @Test
    void fakeFiveMinutesRefusedEvenWhenDigitFiveExistsAsListIndex() {
        String excerpt = """
                # GitLab ligipääs
                1. Logi sisse organisatsiooni teenuste portaali (SSO kaudu).
                2. Vali menüüst Ligipääsutaotlus → GitLab.
                3. Täida taotlusvorm.
                4. Esita taotlus. Süsteem suunab selle sinu otsese juhi kinnitusele.
                5. Pärast juhi kinnitust loob GitLabi administraator konto.
                Juhi kinnitus: tavaliselt 1–2 tööpäeva.
                """;
        List<SourceDto> sources = List.of(
                new SourceDto("gitlab-access.md", "GitLab ligipääs", excerpt));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Logi sisse teenuste portaali ja esita taotlus juhi kinnitusele. "
                        + "Õigused antakse 5 minutiga.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused(), response.toString());
        assertFalse(response.answer().contains("5 minut"));
    }

    @Test
    void realisticParaphrasedGitlabAnswerPassesWithFullExcerpt() {
        String excerpt = """
                # GitLab ligipääs
                Juhend organisatsiooni sisemise GitLabi ligipääsu taotlemiseks.
                Ligipääsu saavad taotleda kõik organisatsiooni töötajad.
                1. Logi sisse organisatsiooni teenuste portaali (SSO kaudu).
                2. Vali menüüst Ligipääsutaotlus → GitLab.
                3. Täida taotlusvorm: põhjendus, soovitud roll, seotud projektide nimed.
                4. Esita taotlus. Süsteem suunab selle sinu otsese juhi kinnitusele.
                5. Pärast juhi kinnitust loob GitLabi administraator konto või lisab õigused.
                Juhi kinnitus: tavaliselt 1–2 tööpäeva.
                Administraatori seadistus pärast kinnitust: kuni 1 tööpäev.
                """;
        List<SourceDto> sources = List.of(
                new SourceDto("gitlab-access.md", "GitLab ligipääs", excerpt));
        AgentLlmResponse llm = new AgentLlmResponse(
                "GitLabi ligipääsu saamiseks logi sisse organisatsiooni teenuste portaali SSO kaudu. "
                        + "Vali menüüst Ligipääsutaotlus ja seejärel GitLab. "
                        + "Täida taotlusvorm ning esita see oma otsese juhi kinnitusele. "
                        + "Pärast kinnitust loob administraator konto või lisab õigused. "
                        + "Juhi kinnitus võtab tavaliselt 1–2 tööpäeva.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused(), response.toString());
        assertEquals("high", response.confidence());
        assertTrue(response.answer().contains("[allikas: gitlab-access.md]"));
    }

    @Test
    void realisticParaphrasedCodeReviewAnswerPasses() {
        String excerpt = """
                # Koodireview enne merge'i
                Iga merge request (MR) protected harusse vajab vähemalt ühe approve'i teise tiimiliikme poolt.
                Autor ei tohi ise oma MR-i approve'ida.
                Ava GitLabis merge request ja loe kirjeldust.
                Veendu, et CI pipeline on roheline.
                Vaata diffi: loogikavead, turvalisus, testide olemasolu.
                Jäta konkreetsed kommentaarid ridadele; erista blokeeriv vs soovitus.
                Approve või request changes.
                Esmane review: eesmärk 1 tööpäev pärast MR-i valmis märkimist.
                """;
        List<SourceDto> sources = List.of(
                new SourceDto("code-review.md", "Koodireview enne merge'i", excerpt));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Enne merge'i tee koodireview GitLabis. "
                        + "Iga MR protected harusse vajab vähemalt ühe teise tiimiliikme approve'i. "
                        + "Veendu et pipeline on roheline, loe diffi ja jäta kommentaarid. "
                        + "Autor ei tohi ise approve'ida. "
                        + "Esmane review eesmärk on 1 tööpäev.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertFalse(response.refused(), response.toString());
        assertEquals("high", response.confidence());
        assertTrue(response.answer().contains("[allikas: code-review.md]"));
    }

    @Test
    void unsupportedSlaFactRefused() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. Maintaineri õigused antakse automaatselt 5 minutiga.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused(), response.toString());
        assertEquals(UNGROUNDED_ANSWER, response.answer());
        assertEquals(UNGROUNDED_REASON, response.refusalReason());
        assertFalse(response.answer().contains("5 minut"));
    }

    @Test
    void outOfScopeCapitalRefused() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. Eesti pealinn on Tallinn.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused(), response.toString());
        assertEquals(UNGROUNDED_ANSWER, response.answer());
        assertEquals(UNGROUNDED_REASON, response.refusalReason());
        assertFalse(response.answer().toLowerCase().contains("tallinn"));
    }

    @Test
    void phishingUrlRefused() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis aadressil https://audit.invalid/verify.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused(), response.toString());
        assertEquals(UNGROUNDED_ANSWER, response.answer());
        assertEquals(UNGROUNDED_REASON, response.refusalReason());
        assertFalse(response.answer().contains("audit.invalid"));
    }

    @Test
    void groundingDisabledAllowsUngroundedAnswer() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. Maintaineri õigused antakse automaatselt 5 minutiga.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources, false);

        assertFalse(response.refused(), response.toString());
        assertTrue(response.answer().contains("5 minut"));
        assertTrue(response.answer().contains("[allikas: gitlab-access.md]"));
        assertEquals("high", response.confidence());
    }

    @Test
    void groundingEnabledByDefaultStillRefusesUngroundedAnswer() {
        List<SourceDto> sources = List.of(
                new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Esita taotlus juhi kinnitusele."));
        AgentLlmResponse llm = new AgentLlmResponse(
                "Taotle ligipääsu teenuste portaalis. Maintaineri õigused antakse automaatselt 5 minutiga.",
                false,
                null,
                "high");

        AskResponse response = AgentService.applyPostRules(llm, sources);

        assertTrue(response.refused(), response.toString());
        assertEquals(UNGROUNDED_ANSWER, response.answer());
        assertEquals(UNGROUNDED_REASON, response.refusalReason());
    }
}
