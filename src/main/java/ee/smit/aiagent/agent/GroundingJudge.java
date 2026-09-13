package ee.smit.aiagent.agent;

import ee.smit.aiagent.model.SourceDto;

import java.util.List;

@FunctionalInterface
public interface GroundingJudge {

    boolean isGrounded(String answer, List<SourceDto> sources);

    static GroundingJudge rejectAll() {
        return (answer, sources) -> false;
    }

    static GroundingJudge acceptAll() {
        return (answer, sources) -> true;
    }
}
