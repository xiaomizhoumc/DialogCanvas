package dialogcanvas.placeholder;

import dialogcanvas.engine.condition.PlaceholderResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/** Main-thread parsing with a lazily linked optional PlaceholderAPI bridge. */
public final class PlaceholderApiResolver implements PlaceholderResolver {
    private final Logger logger;
    private final BooleanSupplier available;
    private final BiFunction<Player, String, String> parser;

    public PlaceholderApiResolver(Logger logger) {
        this(logger, () -> Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"), Bridge::parse);
    }
    /** Backend injection for tests and alternative placeholder providers. */
    public PlaceholderApiResolver(Logger logger, BooleanSupplier available, BiFunction<Player, String, String> parser) {
        this.logger = Objects.requireNonNull(logger);
        this.available = Objects.requireNonNull(available); this.parser = Objects.requireNonNull(parser);
    }
    @Override public Optional<String> resolve(Player player, String text) {
        Result result = resolveResult(player, text);
        return result.success() ? Optional.of(result.value()) : Optional.empty();
    }
    @Override public Result resolveResult(Player player, String text) {
        return resolveResult(player, text, "placeholder");
    }
    @Override public Result resolveResult(Player player, String text, String location) {
        if (!Bukkit.isPrimaryThread()) return failed("NOT_MAIN_THREAD", location);
        if (player == null || text == null) return failed("INVALID_PLACEHOLDER_INPUT", location);
        // Plain text does not require the optional plugin.
        if (text.indexOf('%') < 0) return Result.ok(text);
        try {
            if (!available.getAsBoolean()) return failed("PLACEHOLDERAPI_UNAVAILABLE", location);
            Result result = Result.validate(parser.apply(player, text));
            return result.success() ? result : failed(result.error(), location);
        } catch (RuntimeException | LinkageError failure) {
            return failed("PLACEHOLDER_EXCEPTION", location);
        }
    }
    private Result failed(String error, String location) {
        // Avoid writing player input or expansion output (potential secrets) into the log.
        logger.warning("Placeholder resolution failed [" + error + "] at " + PlaceholderResolver.logLocation(location));
        return new Result(false, null, error, true);
    }
    private static final class Bridge {
        static String parse(Player player, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
    }
}
