package dialogcanvas.dialog;

import dialogcanvas.model.MenuDefinition;

/** Dedicated server dialogs never pause; legacy fields remain readable. */
public final class DialogBehavior {
    private DialogBehavior() {}

    public static boolean effectivePause(MenuDefinition menu) {
        return false;
    }
}
