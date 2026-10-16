package dialogcanvas.engine.condition;

/** ERROR is always false and cannot become PASS through negation or an OR branch. */
public record ConditionResult(Status status, String path, String errorCode, String message) {
    public enum Status { PASS, FAIL, ERROR }
    public boolean passed() { return status == Status.PASS; }
}
