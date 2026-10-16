package dialogcanvas.engine.action;

import dialogcanvas.dialog.TextRenderer;
import dialogcanvas.model.ItemActionDefinition;
import org.bukkit.*;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import java.util.Locale;

/** Bukkit item creation stays in the transaction's existing main-thread boundary. */
public final class ActionItemFactory {
    private final TextRenderer texts = new TextRenderer();

    public ItemStack create(ItemActionDefinition definition, boolean delivery) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Create action items through Scheduler.sync");
        String id = definition.getMaterial();
        var type = Registry.ITEM.get(key(id));
        if (type == null || type.getKey().equals(NamespacedKey.minecraft("air")))
            throw new IllegalArgumentException("无法匹配服务端物品注册名；不会按私有插件名称生成替代物品");
        ItemStack stack = type.createItemStack();
        var meta = stack.getItemMeta();
        if (definition.getName() != null) meta.displayName(texts.render(definition.getName()));
        if (definition.getLore() != null) meta.lore(definition.getLore().stream().map(texts::render).toList());
        if (definition.getUnbreakable() != null) meta.setUnbreakable(definition.getUnbreakable());
        if (definition.getEnchantments() != null) {
            if (definition.getEnchantments().size() > 32) throw new IllegalArgumentException("最多配置 32 项附魔");
            var seen = new java.util.HashSet<NamespacedKey>();
            for (var entry : definition.getEnchantments().entrySet()) {
                NamespacedKey key = key(entry.getKey());
                var enchantment = Registry.ENCHANTMENT.get(key);
                Integer level = entry.getValue();
                if (enchantment == null || !seen.add(key) || level == null || level < 1 || level > 255)
                    throw new IllegalArgumentException("附魔注册名或等级不合法；等级范围为 1～255");
                // Explicit administrator configuration permits above-vanilla levels and combinations.
                if (!meta.addEnchant(enchantment, level, true)) throw new IllegalArgumentException("无法应用配置的附魔");
            }
        }
        if (definition.getAttributes() != null && !definition.getAttributes().isEmpty()) {
            if (definition.getAttributes().size() > 32) throw new IllegalArgumentException("最多配置 32 项属性");
            meta.setAttributeModifiers(type.getDefaultAttributeModifiers());
            for (int i = 0; i < definition.getAttributes().size(); i++) {
                var value = definition.getAttributes().get(i);
                if (value == null || value.getAmount() == null || !Double.isFinite(value.getAmount()) || Math.abs(value.getAmount()) > 1_000_000)
                    throw new IllegalArgumentException("属性数值必须为有限数字，绝对值不超过 1000000");
                var attribute = Registry.ATTRIBUTE.get(key(value.getAttribute()));
                var slot = EquipmentSlotGroup.getByName(value.getSlot() == null ? "mainhand" : value.getSlot());
                if (attribute == null || slot == null) throw new IllegalArgumentException("无法匹配属性注册名或生效部位");
                var operation = AttributeModifier.Operation.valueOf(value.getOperation() == null ? "ADD_NUMBER" : value.getOperation());
                if (!meta.addAttributeModifier(attribute, new AttributeModifier(new NamespacedKey("dialogcanvas", "action_attribute_" + i), value.getAmount(), operation, slot)))
                    throw new IllegalArgumentException("无法应用配置的属性");
            }
        }
        if (!delivery && definition.getGlow() != null) meta.setEnchantmentGlintOverride(definition.getGlow());
        if (!stack.setItemMeta(meta)) throw new IllegalArgumentException("物品元数据无法应用");
        return stack;
    }

    public static NamespacedKey key(String value) {
        if (value == null) throw new IllegalArgumentException("注册名不能为空");
        NamespacedKey key = NamespacedKey.fromString(value.toLowerCase(Locale.ROOT));
        if (key == null) throw new IllegalArgumentException("注册名不合法");
        return key;
    }
}
