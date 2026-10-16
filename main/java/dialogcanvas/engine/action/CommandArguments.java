package dialogcanvas.engine.action;

import java.util.Set;
import java.util.regex.Pattern;

/** Literal arguments are administrator configuration; substituted input stays a single safe token. */
public final class CommandArguments {
    public static final Set<String> EXECUTORS = Set.of("player", "op", "console");
    private static final Pattern INPUT = Pattern.compile("[A-Za-z0-9_.:+-]{1,128}");
    private CommandArguments() {}
    public static boolean literal(String value) {
        return value != null && !value.isBlank() && value.length() <= 2048
                && value.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029);
    }
    public static boolean input(String value) { return value != null && INPUT.matcher(value).matches(); }
}
