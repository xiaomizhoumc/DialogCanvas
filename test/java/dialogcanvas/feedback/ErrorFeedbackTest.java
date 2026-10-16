package dialogcanvas.feedback;

import dialogcanvas.engine.action.ActionChainResult;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ErrorFeedbackTest {
    private final UUID operation = UUID.randomUUID();
    private ActionChainResult result(ActionChainResult.Status status) {
        return new ActionChainResult(operation, status, "test-private-payload E:\\server\\secret.yml");
    }
    @Test void onlyConfirmedCompensationReportsRollback() {
        assertEquals("TRANSACTION_ROLLED_BACK", ErrorFeedback.transactionCode(result(ActionChainResult.Status.ROLLED_BACK), false));
        assertEquals("TRANSACTION_REJECTED", ErrorFeedback.transactionCode(result(ActionChainResult.Status.REJECTED), false));
        assertEquals("RECOVERY_REQUIRED", ErrorFeedback.transactionCode(result(ActionChainResult.Status.ROLLED_BACK), true));
        assertFalse(ErrorFeedback.player("TRANSACTION_REJECTED").contains("回滚"));
    }
    @Test void committedAndRecoveryFailuresStayDistinct() {
        assertEquals("POST_COMMIT_FAILED", ErrorFeedback.transactionCode(result(ActionChainResult.Status.POST_COMMIT_FAILED), false));
        assertEquals("RECOVERY_SAVE_FAILED", ErrorFeedback.transactionCode(result(ActionChainResult.Status.RECOVERY_SAVE_FAILED), true));
        assertFalse(ErrorFeedback.player("POST_COMMIT_FAILED").contains("回滚"));
        assertFalse(ErrorFeedback.player("RECOVERY_REQUIRED").contains("已回滚"));
        assertTrue(result(ActionChainResult.Status.POST_COMMIT_FAILED).stateCommitted());
    }
    @Test void adminGetsSafeCodeAndOperationWhilePlayerGetsShortHint() {
        assertTrue(ErrorFeedback.admin("POST_COMMIT_FAILED", operation).contains(operation.toString()));
        assertTrue(ErrorFeedback.admin("POST_COMMIT_FAILED").contains("诊断码"));
        assertFalse(ErrorFeedback.player("POST_COMMIT_FAILED").contains(operation.toString()));
        assertFalse(ErrorFeedback.admin("E:\\private\\value").contains("private"));
    }
    @Test void conflictsAndPartialCommitHaveDifferentInstructions() {
        assertTrue(ErrorFeedback.admin("REVISION_CONFLICT").contains("合并"));
        assertTrue(ErrorFeedback.admin("YAML_SYNC_CONFLICT").contains("dcmenu reload"));
        assertTrue(ErrorFeedback.admin("MENU_REFERENCED").contains("引用"));
        assertTrue(ErrorFeedback.admin("YAML_SYNC_FAILED").contains("JSON 和内存菜单已保存"));
        assertTrue(ErrorFeedback.admin("YAML_SYNC_FAILED").contains("不要重复提交"));
    }
}
