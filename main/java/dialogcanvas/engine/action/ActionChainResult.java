package dialogcanvas.engine.action;

import java.util.UUID;

public record ActionChainResult(UUID transactionId, Status status, String message, FailureCode failureCode) {
    public enum FailureCode { NONE, INSUFFICIENT_FUNDS, UNKNOWN }
    public ActionChainResult(UUID transactionId, Status status, String message) {
        this(transactionId, status, message, status == Status.COMMITTED ? FailureCode.NONE : FailureCode.UNKNOWN);
    }
    public enum Status {
        COMMITTED, ROLLED_BACK, RECOVERY_REQUIRED, RECOVERY_SAVE_FAILED, POST_COMMIT_FAILED, REJECTED
    }
    public boolean successful() { return status == Status.COMMITTED; }
    /** Quota adapters must count a purchase even when a post-commit notification failed. */
    public boolean stateCommitted() { return status == Status.COMMITTED || status == Status.POST_COMMIT_FAILED; }
}
