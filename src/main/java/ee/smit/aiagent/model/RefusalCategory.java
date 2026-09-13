package ee.smit.aiagent.model;

public enum RefusalCategory {
    SECURITY(
            "Kahjuks ei saa ma selle päringuga jätkata. Palun esita tavaline küsimus IT teenuste teadmusbaasi kohta.",
            "Keeldutud turvapoliitika alusel"),
    OUT_OF_SCOPE(
            "See küsimus jääb IT teenuste teadmusbaasi skoobist välja.",
            "Teema väljaspool skoopi"),
    NO_SOURCE(
            "Teadmusbaasist ei leitud sobivat allikat.",
            "Allikat ei leitud"),
    UNGROUNDED(
            "Ei saa kinnitada vastust teadmusbaasi allikatega.",
            "Vastust ei saanud allikatega kinnitada");

    private final String answer;
    private final String refusalReason;

    RefusalCategory(String answer, String refusalReason) {
        this.answer = answer;
        this.refusalReason = refusalReason;
    }

    public String answer() {
        return answer;
    }

    public String refusalReason() {
        return refusalReason;
    }
}
