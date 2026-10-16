package dialogcanvas.engine;

import dialogcanvas.model.ActionDefinition;
import dialogcanvas.model.MenuDefinition;
import io.papermc.paper.dialog.DialogResponseView;
import org.bukkit.entity.Player;

/** Placeholder: rendering a menu does not implement purchasing or other business actions. */
public final class NoOpActionEngine implements ActionEngine {
    @Override
    public void execute(Player player, MenuDefinition menu, ActionDefinition button, DialogResponseView response) {
        // TODO Validate input, evaluate conditions and execute the transactional action chain.
    }
}
