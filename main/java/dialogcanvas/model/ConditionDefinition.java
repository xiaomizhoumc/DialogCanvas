package dialogcanvas.model;

import java.util.List;

/** Recursive condition tree. Groups use operator/items; leaves use type and their operands. */
public final class ConditionDefinition extends ModelPojo {
    private String comparison;
    private ValueSourceDefinition left;
    private ValueSourceDefinition right;

    public String getComparison() { return comparison; }
    public void setComparison(String comparison) { this.comparison = comparison; }
    public ValueSourceDefinition getLeft() { return left; }
    public void setLeft(ValueSourceDefinition left) { this.left = left; }
    public ValueSourceDefinition getRight() { return right; }
    public void setRight(ValueSourceDefinition right) { this.right = right; }

    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private String operator;

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    private List<ConditionDefinition> items;

    public List<ConditionDefinition> getItems() {
        return items;
    }

    public void setItems(List<ConditionDefinition> items) {
        this.items = items;
    }

    private Boolean negate;

    public Boolean getNegate() {
        return negate;
    }

    public void setNegate(Boolean negate) {
        this.negate = negate;
    }

    private String permission;

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    private String placeholder;

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
