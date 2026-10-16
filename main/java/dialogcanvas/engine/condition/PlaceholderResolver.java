package dialogcanvas.engine.condition;

import java.util.Optional;
import org.bukkit.entity.Player;

/** Main-thread adapter. Return empty when unavailable or unresolved; no implicit PAPI dependency. */
@FunctionalInterface
public interface PlaceholderResolver {
    Optional<String> resolve(Player player, String placeholder);

    /** Unified result API; existing Optional adapters remain usable. */
    default Result resolveResult(Player player, String text) {
        try {
            Optional<String> value = resolve(player, text);
            return value == null || value.isEmpty() ? Result.failure("PLACEHOLDER_UNAVAILABLE")
                    : Result.validate(value.get());
        } catch (RuntimeException | LinkageError failure) {
            return Result.failure("PLACEHOLDER_EXCEPTION: " + failure.getClass().getSimpleName());
        }
    }

    /** Checked boundary for injected providers; location contains caller metadata, never input/output. */
    default Result resolveResult(Player player, String text, String location) {
        try {
            Result result = resolveResult(player, text);
            if (result == null) return Result.failure("NULL_PLACEHOLDER_RESULT");
            return result.success() ? Result.validate(result.value()) : result;
        } catch (RuntimeException | LinkageError failure) {
            return Result.failure("PLACEHOLDER_EXCEPTION");
        }
    }

    static String logLocation(String location) {
        return location != null && location.length() <= 240 && location.matches("[A-Za-z0-9_.\\[\\]:= /-]+")
                ? location : "placeholder";
    }

    /** warningLogged prevents a second WARN when the condition engine consumes this failure. */
    record Result(boolean success, String value, String error, boolean warningLogged) {
        public Result(boolean success, String value, String error) { this(success, value, error, false); }
        public Result {
            if (success && (value == null || error != null)) throw new IllegalArgumentException("Invalid success result");
            if (!success && (value != null || error == null || error.isBlank())) throw new IllegalArgumentException("Invalid failure result");
        }
        public static Result ok(String value) { return new Result(true, value, null); }
        public static Result failure(String error) { return new Result(false, null, error); }
        /** Provider error descriptions may contain secrets. Only known codes are diagnostic output. */
        public String diagnosticCode() {
            if (success) return "OK";
            if (error.equals("PLACEHOLDER_EXCEPTION") || error.startsWith("PLACEHOLDER_EXCEPTION: ")) return "PLACEHOLDER_EXCEPTION";
            return switch (error) {
                case "PLACEHOLDER_UNAVAILABLE", "PLACEHOLDERAPI_UNAVAILABLE", "NULL_PLACEHOLDER_RESULT",
                     "UNRESOLVED_PERCENT", "NOT_MAIN_THREAD", "INVALID_PLACEHOLDER_INPUT" -> error;
                default -> "PLACEHOLDER_PROVIDER_FAILED";
            };
        }
        @Override public String toString() { return "Result[success=" + success + ", code=" + diagnosticCode() + ", warningLogged=" + warningLogged + "]"; }
        public static Result validate(String value) {
            if (value == null) return failure("NULL_PLACEHOLDER_RESULT");
            return value.indexOf('%') >= 0 ? failure("UNRESOLVED_PERCENT") : ok(value);
        }
    }
}
