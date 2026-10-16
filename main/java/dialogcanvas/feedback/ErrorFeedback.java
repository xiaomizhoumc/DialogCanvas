package dialogcanvas.feedback;

import dialogcanvas.model.MenuJson;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import dialogcanvas.engine.action.ActionChainResult;

/** Fixed catalog shared with the frontend. Never formats exception messages or user values. */
public final class ErrorFeedback {
    private static final JsonNode CATALOG = load();
    private ErrorFeedback() {}
    private static JsonNode load() {
        try (var stream = ErrorFeedback.class.getResourceAsStream("/feedback/errorMessages.json")) {
            if (stream == null) throw new IllegalStateException("Feedback catalog missing");
            return MenuJson.createMapper().readTree(stream);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Feedback catalog unavailable"); }
    }
    public static String code(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,79}") ? value : "UNKNOWN_ERROR";
    }
    private static JsonNode hint(String code) { return CATALOG.path(CATALOG.has(code) ? code : "UNKNOWN_ERROR"); }
    public static String player(String code) { return hint(code(code)).path("player").asString(); }
    public static String transactionCode(ActionChainResult value, boolean uncertain) {
        if (value.status() == ActionChainResult.Status.RECOVERY_SAVE_FAILED) return "RECOVERY_SAVE_FAILED";
        if (uncertain) return "RECOVERY_REQUIRED";
        return switch (value.status()) {
            case COMMITTED -> "NONE";
            case ROLLED_BACK -> value.failureCode() == ActionChainResult.FailureCode.INSUFFICIENT_FUNDS
                    ? "INSUFFICIENT_FUNDS" : "TRANSACTION_ROLLED_BACK";
            case REJECTED -> "TRANSACTION_REJECTED";
            case POST_COMMIT_FAILED -> "POST_COMMIT_FAILED";
            case RECOVERY_SAVE_FAILED -> "RECOVERY_SAVE_FAILED";
            case RECOVERY_REQUIRED -> "RECOVERY_REQUIRED";
        };
    }
    public static String admin(String code) { return admin(code, null); }
    public static String admin(String code, UUID operation) {
        code = code(code);
        var hint = hint(code);
        return hint.path("message").asString() + hint.path("advice").asString()
                + "（诊断码：" + code + (operation == null ? "" : "；操作编号：" + operation) + "）";
    }
}
