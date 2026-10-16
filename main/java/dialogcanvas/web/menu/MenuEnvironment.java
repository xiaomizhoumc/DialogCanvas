package dialogcanvas.web.menu;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import java.util.*;

/** Immutable main-thread capability capture; API validation never calls Bukkit from its worker. */
public record MenuEnvironment(Set<String> materials, Set<String> sounds, Set<String> enchantments, Set<String> attributes) {
    public MenuEnvironment(Set<String> materials, Set<String> sounds) { this(materials, sounds, Set.of(), Set.of()); }
    public MenuEnvironment {
        materials = Set.copyOf(materials); sounds = Set.copyOf(sounds);
        enchantments = Set.copyOf(enchantments); attributes = Set.copyOf(attributes);
    }
    public static MenuEnvironment capture() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Capture capabilities through Scheduler.sync");
        var materials = new HashSet<String>(); var sounds = new HashSet<String>();
        for (Material value : Material.values()) if (!value.name().startsWith("LEGACY_") && value.isItem() && !value.isAir()) {
            materials.add(value.name()); materials.add(value.getKey().toString());
        }
        // Namespaced entries are the actual server item registry, not guessed plugin IDs.
        org.bukkit.Registry.ITEM.forEach(value -> {
            if (!value.getKey().equals(org.bukkit.NamespacedKey.minecraft("air"))) materials.add(value.getKey().toString());
        });
        for (var field : Sound.class.getFields()) if (field.getType() == Sound.class) {
            try {
                sounds.add(field.getName());
                Sound value = (Sound) field.get(null);
                sounds.add(io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.SOUND_EVENT).getKeyOrThrow(value).asString());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Sound capability capture failed", failure); }
        }
        var enchantments = new HashSet<String>(); var attributes = new HashSet<String>();
        org.bukkit.Registry.ENCHANTMENT.forEach(value -> enchantments.add(value.getKey().toString()));
        org.bukkit.Registry.ATTRIBUTE.forEach(value -> attributes.add(value.getKey().toString()));
        return new MenuEnvironment(materials, sounds, enchantments, attributes);
    }
}
