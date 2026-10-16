package dialogcanvas.dialog;

import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.web.menu.MenuRepository;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Native, synchronous sneak + swap-hand gesture; uses only the committed main menu. */
public final class MainMenuShortcut implements Listener, AutoCloseable {
    private final Supplier<MenuRepository> menus;
    private final BiFunction<Player, String, CompletableFuture<Void>> open;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public MainMenuShortcut(Scheduler scheduler, Supplier<MenuRepository> menus,
                            BiFunction<Player, String, CompletableFuture<Void>> open) {
        this.menus = menus; this.open = open;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        // This native event is synchronous; do not schedule its event mutation later.
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Swap event must run on main");
        if (closed || event.isCancelled()) return;
        Player player = event.getPlayer();
        if (!player.isOnline() || !player.isSneaking() || !player.hasPermission("dcmenu.shortcut")) return;
        MenuRepository repository = menus.get();
        String id = repository == null ? null : repository.mainMenuId();
        if (id == null) return; // Preserve vanilla swap when there is no usable shortcut.
        event.setCancelled(true);
        UUID uuid = player.getUniqueId();
        if (!pending.add(uuid)) return;
        try { open.apply(player, id).whenComplete((ignored, failure) -> pending.remove(uuid)); }
        catch (RuntimeException failure) { pending.remove(uuid); throw failure; }
    }

    @Override public void close() { closed = true; pending.clear(); }
}
