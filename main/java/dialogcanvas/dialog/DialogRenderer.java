package dialogcanvas.dialog;

import dialogcanvas.engine.ActionEngine;
import dialogcanvas.engine.ClickGuard;
import dialogcanvas.dialog.icon.VanillaIconResolver;
import dialogcanvas.model.*;
import dialogcanvas.scheduler.Scheduler;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Creates dynamic per-player dialogs; all Paper/Bukkit calls stay on the main thread. */
public final class DialogRenderer implements Listener, AutoCloseable {
    private final Scheduler scheduler;
    private final ActionEngine actionEngine;
    private final ClickGuard clickGuard = new ClickGuard();
    private final Logger logger;
    private final TextRenderer textRenderer = new TextRenderer();
    private final VanillaIconResolver icons = new VanillaIconResolver();
    private final JsonMapper mapper = MenuJson.createMapper();
    private final Function<MenuDefinition, BooleanSupplier> versions;
    private final Clock clock;
    private final Map<UUID, Session> sessions = new HashMap<>(); // Main-thread only.
    private boolean closed;

    static final class Session {
        final Player owner;
        final BooleanSupplier currentVersion;
        Session(Player owner, BooleanSupplier currentVersion) {
            this.owner = owner; this.currentVersion = currentVersion;
        }
    }

    /** Standalone/test renderer: source documents are trusted; no live repository version exists. */
    public DialogRenderer(Scheduler scheduler, ActionEngine actionEngine, Logger logger) {
        this(scheduler, actionEngine, logger, ignored -> () -> true, Clock.systemUTC());
    }

    public DialogRenderer(Scheduler scheduler, ActionEngine actionEngine, Logger logger,
                          Function<MenuDefinition, BooleanSupplier> versions) {
        this(scheduler, actionEngine, logger, versions, Clock.systemUTC());
    }

    DialogRenderer(Scheduler scheduler, ActionEngine actionEngine, Logger logger,
                   Function<MenuDefinition, BooleanSupplier> versions, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.actionEngine = Objects.requireNonNull(actionEngine);
        this.logger = Objects.requireNonNull(logger);
        this.versions = Objects.requireNonNull(versions);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Safe entry point from an HTTP thread. Completion includes constructing and showing the dialog. */
    public CompletableFuture<Void> show(Player player, MenuDefinition menu) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(menu);
        // Freeze the caller's mutable POJO so later edits cannot change an already bound callback.
        final MenuDefinition snapshot;
        try {
            snapshot = snapshot(menu);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return scheduler.sync(() -> {
            if (!player.isOnline()) {
                throw new IllegalStateException("Cannot show a dialog to an offline player");
            }
            player.showDialog(create(snapshot, player));
        });
    }

    /** Main-thread-only conversion for integrations that need the Dialog object directly. */
    public Dialog render(MenuDefinition menu, Player player) {
        requireMainThread();
        return create(snapshot(Objects.requireNonNull(menu)), Objects.requireNonNull(player));
    }

    private MenuDefinition snapshot(MenuDefinition menu) {
        return mapper.readValue(mapper.writeValueAsString(menu), MenuDefinition.class);
    }

    private Dialog create(MenuDefinition menu, Player player) {
        requireMainThread();
        if (closed || !player.isOnline()) throw new IllegalStateException("Dialog renderer closed or player offline");
        // Capture committed source identity BEFORE binding player-specific inventory limits.
        Session session = new Session(player, versions.apply(snapshot(menu)));
        if (!session.currentVersion.getAsBoolean()) throw new IllegalStateException("Menu version is no longer current; reopen menu");
        bindPlayerInputs(menu, player);
        if (menu.getSchemaVersion() != null && menu.getSchemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + menu.getSchemaVersion());
        }
        if (menu.getDialogType() == null) {
            throw new IllegalArgumentException("dialogType is required");
        }
        var base = DialogBase.builder(textRenderer.render(menu.getTitle()));
        if (menu.getExternalTitle() != null) base.externalTitle(textRenderer.render(menu.getExternalTitle()));
        if (menu.getCanCloseWithEscape() != null) base.canCloseWithEscape(menu.getCanCloseWithEscape());
        // This plugin targets dedicated servers, so native pause is always disabled.
        base.pause(DialogBehavior.effectivePause(menu));
        if (menu.getAfterAction() != null) {
            base.afterAction(DialogBase.DialogAfterAction.valueOf(menu.getAfterAction().name()));
        }
        base.body(orEmpty(menu.getBody()).stream().map(this::body).toList());
        base.inputs(orEmpty(menu.getInputs()).stream().map(this::input).toList());
        DialogType type = switch (menu.getDialogType()) {
            case NOTICE -> menu.getNoticeAction() == null ? DialogType.notice()
                    : DialogType.notice(button(menu.getNoticeAction(), menu, session));
            case CONFIRMATION -> DialogType.confirmation(
                    button(Objects.requireNonNull(menu.getYesButton(), "yesButton is required"), menu, session),
                    button(Objects.requireNonNull(menu.getNoButton(), "noButton is required"), menu, session));
            case MULTI_ACTION -> {
                var builder = DialogType.multiAction(orEmpty(menu.getActions()).stream()
                        .map(action -> button(action, menu, session)).toList());
                if (menu.getExitAction() != null) builder.exitAction(button(menu.getExitAction(), menu, session));
                if (menu.getColumns() != null) builder.columns(positive(menu.getColumns(), "columns"));
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("Dialog type is not implemented: " + menu.getDialogType());
        };
        DialogBase builtBase = base.build();
        Dialog dialog = Dialog.create(factory -> factory.empty().base(builtBase).type(type));
        activate(session);
        return dialog;
    }

    /** Resolve per-player inventory caps on the already detached menu snapshot. */
    MenuDefinition bindPlayerInputs(MenuDefinition menu, Player player) {
        requireMainThread();
        for (InputDefinition input : orEmpty(menu.getInputs())) {
            String materialName = switch (input) {
                case InputDefinition.NumberRange range -> range.getInventoryMaximumMaterial();
                case InputDefinition.Text text -> text.getInventoryMaximumMaterial();
                default -> null;
            };
            if (materialName == null) continue;
            Material material = Material.matchMaterial(materialName);
            if (material == null || !material.isItem() || material.isAir()) {
                throw new IllegalArgumentException("Invalid inventory maximum material: " + materialName);
            }
            int available = inventoryCount(player, new ItemStack(material));
            if (input instanceof InputDefinition.NumberRange range) {
                double configuredEnd = Objects.requireNonNull(range.getEnd(), "number_range.end is required");
                double end = Math.min(configuredEnd, available);
                if (range.getStart() != null && end < range.getStart()) range.setStart(end);
                range.setEnd(end);
                double initial = range.getInitial() == null ? Objects.requireNonNull(range.getStart(), "number_range.start is required") : range.getInitial();
                range.setInitial(Math.max(range.getStart(), Math.min(initial, end)));
            } else if (input instanceof InputDefinition.Text text) {
                double maximum = text.getMaximum() == null ? available : Math.min(text.getMaximum(), available);
                text.setMaximum(maximum);
                if (text.getMinimum() != null && maximum < text.getMinimum()) text.setMinimum(maximum);
                if (text.getInitial() != null && Boolean.TRUE.equals(text.getNumeric())) {
                    try {
                        double initial = Double.parseDouble(text.getInitial().trim());
                        double minimum = text.getMinimum() == null ? -Double.MAX_VALUE : text.getMinimum();
                        text.setInitial(formatNumber(Math.max(minimum, Math.min(initial, maximum)), Boolean.TRUE.equals(text.getInteger())));
                    } catch (NumberFormatException failure) {
                        throw new IllegalArgumentException("Invalid numeric text initial value", failure);
                    }
                }
            }
        }
        return menu;
    }

    private static int inventoryCount(Player player, ItemStack prototype) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && !stack.getType().isAir() && stack.isSimilar(prototype)) count = Math.addExact(count, stack.getAmount());
        }
        return count;
    }

    private static String formatNumber(double value, boolean integer) {
        return integer ? Long.toString((long) value) : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private DialogBody body(BodyBlock block) {
        return switch (Objects.requireNonNull(block)) {
            case BodyBlock.Text text -> textBody(text);
            case BodyBlock.Item item -> {
                Material material = Material.matchMaterial(Objects.requireNonNull(item.getMaterial(), "material is required"));
                if (material == null || !material.isItem() || material.isAir()) {
                    throw new IllegalArgumentException("Invalid item material: " + item.getMaterial());
                }
                ItemStack stack = new ItemStack(material, item.getAmount() == null ? 1 : positive(item.getAmount(), "amount"));
                var meta = stack.getItemMeta();
                if (item.getName() != null) meta.displayName(textRenderer.render(item.getName()));
                if (item.getLore() != null) meta.lore(item.getLore().stream().map(textRenderer::render).toList());
                if (item.getGlow() != null) meta.setEnchantmentGlintOverride(item.getGlow());
                stack.setItemMeta(meta);
                var builder = DialogBody.item(stack);
                if (item.getDescription() != null) builder.description(textBody(item.getDescription()));
                if (item.getShowDecorations() != null) builder.showDecorations(item.getShowDecorations());
                if (item.getShowTooltip() != null) builder.showTooltip(item.getShowTooltip());
                if (item.getWidth() != null) builder.width(dimension(item.getWidth(), 256, "item.width"));
                if (item.getHeight() != null) builder.height(dimension(item.getHeight(), 256, "item.height"));
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("Unsupported body block");
        };
    }

    private PlainMessageDialogBody textBody(BodyBlock.Text text) {
        if (text.getColorMode() != null || text.getColor() != null) {
            throw new IllegalArgumentException("Migrate colorMode/color to TextSpec before rendering");
        }
        var contents = textRenderer.render(text.getText());
        return text.getWidth() == null ? DialogBody.plainMessage(contents)
                : DialogBody.plainMessage(contents, dimension(text.getWidth(), 1024, "body.width"));
    }

    private DialogInput input(InputDefinition input) {
        String key = Objects.requireNonNull(input.getKey(), "input.key is required");
        var label = textRenderer.render(input.getLabel());
        return switch (input) {
            case InputDefinition.Bool bool -> {
                var builder = DialogInput.bool(key, label);
                if (bool.getInitial() != null) builder.initial(bool.getInitial());
                if (bool.getOnTrue() != null) builder.onTrue(bool.getOnTrue());
                if (bool.getOnFalse() != null) builder.onFalse(bool.getOnFalse());
                yield builder.build();
            }
            case InputDefinition.Text text -> {
                var builder = DialogInput.text(key, label);
                if (text.getInitial() != null) builder.initial(text.getInitial());
                if (text.getWidth() != null) builder.width(dimension(text.getWidth(), 1024, "input.width"));
                if (text.getLabelVisible() != null) builder.labelVisible(text.getLabelVisible());
                if (text.getMaxLength() != null) builder.maxLength(positive(text.getMaxLength(), "maxLength"));
                if (text.getMultiline() != null) {
                    var multiline = text.getMultiline();
                    if (multiline.getMaxLines() != null) positive(multiline.getMaxLines(), "maxLines");
                    if (multiline.getHeight() != null) dimension(multiline.getHeight(), 512, "multiline.height");
                    builder.multiline(TextDialogInput.MultilineOptions.create(multiline.getMaxLines(), multiline.getHeight()));
                }
                yield builder.build();
            }
            case InputDefinition.NumberRange range -> {
                float start = finite(range.getStart(), "start"), end = finite(range.getEnd(), "end");
                if (start > end) throw new IllegalArgumentException("number_range requires start <= end");
                var builder = DialogInput.numberRange(key, label, start, end);
                if (range.getWidth() != null) builder.width(dimension(range.getWidth(), 1024, "input.width"));
                if (range.getLabelFormat() != null) builder.labelFormat(range.getLabelFormat());
                if (range.getInitial() != null) {
                    float initial = finite(range.getInitial(), "initial");
                    if (initial < start || initial > end) throw new IllegalArgumentException("initial is outside range");
                    builder.initial(initial);
                }
                if (range.getStep() != null) {
                    float step = finite(range.getStep(), "step");
                    if (step <= 0) throw new IllegalArgumentException("step must be positive");
                    builder.step(step);
                }
                yield builder.build();
            }
            case InputDefinition.SingleOption option -> {
                var entries = orEmpty(option.getOptions()).stream().map(value -> SingleOptionDialogInput.OptionEntry.create(
                        Objects.requireNonNull(value.getId(), "option.id is required"),
                        value.getDisplay() == null ? null : textRenderer.render(value.getDisplay()),
                        Boolean.TRUE.equals(value.getInitial()))).toList();
                if (entries.isEmpty()) throw new IllegalArgumentException("options must not be empty");
                if (entries.stream().map(SingleOptionDialogInput.OptionEntry::id).distinct().count() != entries.size()
                        || entries.stream().filter(SingleOptionDialogInput.OptionEntry::initial).count() > 1) {
                    throw new IllegalArgumentException("Options need unique IDs and at most one initial selection");
                }
                var builder = DialogInput.singleOption(key, label, entries);
                if (option.getWidth() != null) builder.width(dimension(option.getWidth(), 1024, "input.width"));
                if (option.getLabelVisible() != null) builder.labelVisible(option.getLabelVisible());
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("Unsupported input");
        };
    }

    private ActionButton button(ActionDefinition action, MenuDefinition menu, Session session) {
        var builder = ActionButton.builder(buttonLabel(action, menu));
        if (action.getTooltip() != null) builder.tooltip(textRenderer.render(action.getTooltip()));
        if (action.getWidth() != null) builder.width(dimension(action.getWidth(), 1024, "button.width"));
        String kind = action.getActionType() == null ? "custom" : action.getActionType();
        if ("none".equals(kind)) return builder.build();
        if (!"custom".equals(kind) && !"close".equals(kind)) {
            throw new IllegalArgumentException("Callback renderer does not support actionType: " + kind);
        }
        if (action.getAdditions() != null || action.getTemplate() != null || action.getClickEvent() != null) {
            throw new IllegalArgumentException("Raw additions/templates/click events are not supported by callback buttons");
        }
        var options = ClickCallback.Options.builder();
        if (action.getCallbackOptions() != null) {
            var configured = action.getCallbackOptions();
            if (configured.getUses() != null) options.uses(positive(configured.getUses(), "callbackOptions.uses"));
            if (configured.getLifetime() != null) {
                Duration lifetime = Duration.parse(configured.getLifetime());
                if (lifetime.isNegative() || lifetime.isZero()) throw new IllegalArgumentException("Callback lifetime must be positive");
                options.lifetime(lifetime);
            }
        }
        var policy = options.build();
        builder.action(DialogAction.customClick(callback(action, menu, session, kind, policy), policy));
        return builder.build();
    }

    // Pure component assembly is independently testable without native Dialog builder providers.
    net.kyori.adventure.text.Component buttonLabel(ActionDefinition action, MenuDefinition menu) {
        VanillaIconResolver.Result result = icons.resolve(action.getIcon());
        if (result.error() != null) logger.warning("Button icon omitted: menu=" + menu.getId()
                + ", button=" + action.getId() + ", reason=" + result.error() + "; keeping label text");
        return textRenderer.renderButtonLabel(action.getLabel(), result.icon());
    }

    // Kept independent of Paper's builder provider so callback thread/owner boundaries are testable.
    DialogActionCallback callback(ActionDefinition action, MenuDefinition menu, Session session, String kind,
                                  ClickCallback.Options policy) {
        Instant expires = clock.instant().plus(policy.lifetime());
        int[] remaining = {policy.uses()}; // Callback admissions, accessed only on main.
        return (response, audience) -> scheduler.sync(() -> {
            if (!(audience instanceof Player player) || !valid(session, player)
                    || !clock.instant().isBefore(expires) || remaining[0] == 0 || "none".equals(kind)) return;
            if ("close".equals(kind)) {
                if (remaining[0] > 0) remaining[0]--;
                sessions.remove(player.getUniqueId(), session);
                player.closeDialog(); // UI only: never enter the business engine or acquire a trade lock.
                return;
            }
            clickGuard.execute(player.getUniqueId(), menu.getId(), action.getId(), () -> {
                if (remaining[0] > 0) remaining[0]--;
                if (menu.getAfterAction() == null || menu.getAfterAction() == AfterAction.CLOSE)
                    sessions.remove(player.getUniqueId(), session);
                return actionEngine.executeAsync(player, menu, action, response);
            }).whenComplete((ignored, failure) -> {
                        if (failure != null) logger.warning("Dialog action chain failed [DIALOG_ACTION_CHAIN_FAILED]; inspect transaction records before retrying");
                    });
        }).whenComplete((ignored, failure) -> {
            if (failure != null) logger.warning("Dialog action callback failed [DIALOG_ACTION_CALLBACK_FAILED]");
        });
    }

    private boolean valid(Session session, Player player) {
        return !closed && player == session.owner && player.isOnline()
                && sessions.get(player.getUniqueId()) == session && session.currentVersion.getAsBoolean();
    }

    // Session activation is shared by native conversion and provider-independent callback tests.
    void activate(Session session) {
        requireMainThread();
        if (closed) throw new IllegalStateException("Dialog renderer closed");
        sessions.put(session.owner.getUniqueId(), session);
    }

    @EventHandler
    public void disconnected(PlayerQuitEvent event) {
        invalidate(event.getPlayer());
    }

    /** Invalidate known UI closes without interrupting already admitted transactions. */
    public void invalidate(Player player) {
        requireMainThread();
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.owner == player) sessions.remove(player.getUniqueId());
        // Never release in-flight locks here: rollback/receipts may still be running.
    }

    @Override public void close() {
        requireMainThread();
        closed = true;
        sessions.clear(); // In-flight locks remain owned by their actual operation settlement.
    }

    private static int positive(int value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static int dimension(int value, int maximum, String field) {
        if (value < 1 || value > maximum) throw new IllegalArgumentException(field + " must be in 1.." + maximum);
        return value;
    }

    private static float finite(Double value, String field) {
        if (value == null || !Double.isFinite(value) || !Float.isFinite(value.floatValue())) {
            throw new IllegalArgumentException(field + " must be finite and representable as a float");
        }
        return value.floatValue();
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Dialog conversion must run on the server main thread");
    }
}
