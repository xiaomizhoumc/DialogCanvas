package dialogcanvas.web.menu;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Project registry of immutable validated definitions; distinct from Paper's native registry. */
public class MenuRegistry {
    public record Warning(String path, String code, String message) {}
    public record Entry(String json, String revision, List<Warning> warnings, Set<String> references, boolean shortcutEnabled) {
        public Entry { warnings = List.copyOf(warnings); references = Set.copyOf(references); }
    }
    private record State(Map<String, Entry> entries, String mainMenuId) {}
    private final AtomicReference<State> state = new AtomicReference<>(new State(Map.of(), null));
    public Map<String, Entry> snapshot() { return state.get().entries(); }
    public String mainMenuId() { return state.get().mainMenuId(); }
    /** Prepared immutable map assignment only. Overridable to exercise publication failure recovery. */
    public void publish(Map<String, Entry> snapshot) {
        var main = snapshot.entrySet().stream().filter(entry -> entry.getValue().shortcutEnabled()).map(Map.Entry::getKey).sorted().toList();
        if (main.size() > 1) throw new IllegalArgumentException("快捷主菜单重复设置：" + String.join("、", main));
        state.set(new State(Map.copyOf(snapshot), main.isEmpty() ? null : main.getFirst()));
    }
}
