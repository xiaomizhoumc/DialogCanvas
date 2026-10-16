package dialogcanvas.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Typed body blocks. Legacy color fields are preserved without applying migration semantics. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = BodyBlock.Text.class, name = "text"),
        @JsonSubTypes.Type(value = BodyBlock.Item.class, name = "item")
})
public abstract class BodyBlock extends ModelPojo {
    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public static final class Text extends BodyBlock {
        public Text() {
            setType("text");
        }

        private TextSpec text;

        public TextSpec getText() {
            return text;
        }

        public void setText(TextSpec text) {
            this.text = text;
        }

        private Integer width;

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        private String colorMode;

        public String getColorMode() {
            return colorMode;
        }

        public void setColorMode(String colorMode) {
            this.colorMode = colorMode;
        }

        private String color;

        public String getColor() {
            return color;
        }

        public void setColor(String color) {
            this.color = color;
        }
    }

    public static final class Item extends BodyBlock {
        public Item() {
            setType("item");
        }

        private String material;

        public String getMaterial() {
            return material;
        }

        public void setMaterial(String material) {
            this.material = material;
        }

        private TextSpec name;

        public TextSpec getName() {
            return name;
        }

        public void setName(TextSpec name) {
            this.name = name;
        }

        private List<TextSpec> lore;

        public List<TextSpec> getLore() {
            return lore;
        }

        public void setLore(List<TextSpec> lore) {
            this.lore = lore;
        }

        private Integer amount;

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }

        private Boolean glow;

        public Boolean getGlow() {
            return glow;
        }

        public void setGlow(Boolean glow) {
            this.glow = glow;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
        private BodyBlock.Text description;

        public BodyBlock.Text getDescription() {
            return description;
        }

        public void setDescription(BodyBlock.Text description) {
            this.description = description;
        }

        private Boolean showDecorations;

        public Boolean getShowDecorations() {
            return showDecorations;
        }

        public void setShowDecorations(Boolean showDecorations) {
            this.showDecorations = showDecorations;
        }

        private Boolean showTooltip;

        public Boolean getShowTooltip() {
            return showTooltip;
        }

        public void setShowTooltip(Boolean showTooltip) {
            this.showTooltip = showTooltip;
        }

        private Integer width;

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        private Integer height;

        public Integer getHeight() {
            return height;
        }

        public void setHeight(Integer height) {
            this.height = height;
        }
    }
}
