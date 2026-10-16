package dialogcanvas.model;

/** Optional button icon, configured separately from TextSpec label. */
public final class ButtonIconDefinition extends ModelPojo {
    private String type = "item";
    private String material;
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMaterial() { return material; }
    public void setMaterial(String material) { this.material = material; }
}
