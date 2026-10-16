package dialogcanvas.model;

import java.util.List;
import java.util.Map;

/** Shared item prototype for delivery and exact-metadata removal; quantity stays an expression. */
public abstract class ItemActionDefinition extends ActionStepDefinition {
    private String material;
    private String amount;
    private TextSpec name;
    private List<TextSpec> lore;
    private Boolean glow; // Legacy take-item matching only; delivery uses real enchantments.
    private Map<String, Integer> enchantments;
    private List<ItemAttributeDefinition> attributes;
    private Boolean unbreakable;
    public String getMaterial() { return material; }
    public void setMaterial(String value) { material = value; }
    public String getAmount() { return amount; }
    public void setAmount(String value) { amount = value; }
    public TextSpec getName() { return name; }
    public void setName(TextSpec value) { name = value; }
    public List<TextSpec> getLore() { return lore; }
    public void setLore(List<TextSpec> value) { lore = value; }
    public Boolean getGlow() { return glow; }
    public void setGlow(Boolean value) { glow = value; }
    public Map<String, Integer> getEnchantments() { return enchantments; }
    public void setEnchantments(Map<String, Integer> value) { enchantments = value; }
    public List<ItemAttributeDefinition> getAttributes() { return attributes; }
    public void setAttributes(List<ItemAttributeDefinition> value) { attributes = value; }
    public Boolean getUnbreakable() { return unbreakable; }
    public void setUnbreakable(Boolean value) { unbreakable = value; }
}
