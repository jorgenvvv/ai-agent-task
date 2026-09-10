package ee.smit.aiagent.model;

public record GuardDecision(boolean allowed, GuardReasonCode reasonCode) {

    public static GuardDecision allow() {
        return new GuardDecision(true, null);
    }

    public static GuardDecision refuse(GuardReasonCode reasonCode) {
        return new GuardDecision(false, reasonCode);
    }
}
