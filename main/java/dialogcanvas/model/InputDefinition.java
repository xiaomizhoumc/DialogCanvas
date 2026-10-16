package dialogcanvas.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Typed inputs preserve the different initial-value types. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = InputDefinition.Bool.class, name = "bool"),
        @JsonSubTypes.Type(value = InputDefinition.Text.class, name = "text"),
        @JsonSubTypes.Type(value = InputDefinition.NumberRange.class, name = "number_range"),
        @JsonSubTypes.Type(value = InputDefinition.SingleOption.class, name = "single_option")
})
public abstract class InputDefinition extends ModelPojo {
    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    private TextSpec label;

    public TextSpec getLabel() {
        return label;
    }

    public void setLabel(TextSpec label) {
        this.label = label;
    }

    public static final class Bool extends InputDefinition {
        public Bool() {
            setType("bool");
        }

        private Boolean initial;

        public Boolean getInitial() {
            return initial;
        }

        public void setInitial(Boolean initial) {
            this.initial = initial;
        }

        private String onTrue;

        public String getOnTrue() {
            return onTrue;
        }

        public void setOnTrue(String onTrue) {
            this.onTrue = onTrue;
        }

        private String onFalse;

        public String getOnFalse() {
            return onFalse;
        }

        public void setOnFalse(String onFalse) {
            this.onFalse = onFalse;
        }
    }

    public static final class Text extends InputDefinition {
        public Text() {
            setType("text");
        }

        private String initial;

        public String getInitial() {
            return initial;
        }

        public void setInitial(String initial) {
            this.initial = initial;
        }

        private Integer width;

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        private Integer maxLength;

        public Integer getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(Integer maxLength) {
            this.maxLength = maxLength;
        }

        private Boolean labelVisible;

        public Boolean getLabelVisible() {
            return labelVisible;
        }

        public void setLabelVisible(Boolean labelVisible) {
            this.labelVisible = labelVisible;
        }

        private MultilineDefinition multiline;

        public MultilineDefinition getMultiline() {
            return multiline;
        }

        public void setMultiline(MultilineDefinition multiline) {
            this.multiline = multiline;
        }

        /** Parse this native text field as a finite number before conditions/actions see it. */
        private Boolean numeric;

        /** Reject fractional values when this field represents an item quantity. */
        private Boolean integer;

        private Double minimum;
        private Double maximum;

        /** At render time, cap maximum to the matching quantity in the player's storage inventory. */
        private String inventoryMaximumMaterial;

        public Boolean getNumeric() { return numeric; }
        public void setNumeric(Boolean numeric) { this.numeric = numeric; }
        public Boolean getInteger() { return integer; }
        public void setInteger(Boolean integer) { this.integer = integer; }
        public Double getMinimum() { return minimum; }
        public void setMinimum(Double minimum) { this.minimum = minimum; }
        public Double getMaximum() { return maximum; }
        public void setMaximum(Double maximum) { this.maximum = maximum; }
        public String getInventoryMaximumMaterial() { return inventoryMaximumMaterial; }
        public void setInventoryMaximumMaterial(String inventoryMaximumMaterial) { this.inventoryMaximumMaterial = inventoryMaximumMaterial; }
    }

    public static final class NumberRange extends InputDefinition {
        public NumberRange() {
            setType("number_range");
        }

        private Double start;

        public Double getStart() {
            return start;
        }

        public void setStart(Double start) {
            this.start = start;
        }

        private Double end;

        public Double getEnd() {
            return end;
        }

        public void setEnd(Double end) {
            this.end = end;
        }

        private Double step;

        public Double getStep() {
            return step;
        }

        public void setStep(Double step) {
            this.step = step;
        }

        private Double initial;

        public Double getInitial() {
            return initial;
        }

        public void setInitial(Double initial) {
            this.initial = initial;
        }

        private Integer width;

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        private String labelFormat;

        public String getLabelFormat() {
            return labelFormat;
        }

        public void setLabelFormat(String labelFormat) {
            this.labelFormat = labelFormat;
        }

        /** At render time, cap end to the matching quantity in the player's storage inventory. */
        private String inventoryMaximumMaterial;

        public String getInventoryMaximumMaterial() { return inventoryMaximumMaterial; }
        public void setInventoryMaximumMaterial(String inventoryMaximumMaterial) { this.inventoryMaximumMaterial = inventoryMaximumMaterial; }
    }

    public static final class SingleOption extends InputDefinition {
        public SingleOption() {
            setType("single_option");
        }

        private Integer width;

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        private Boolean labelVisible;

        public Boolean getLabelVisible() {
            return labelVisible;
        }

        public void setLabelVisible(Boolean labelVisible) {
            this.labelVisible = labelVisible;
        }

        private List<OptionDefinition> options;

        public List<OptionDefinition> getOptions() {
            return options;
        }

        public void setOptions(List<OptionDefinition> options) {
            this.options = options;
        }
    }
}
