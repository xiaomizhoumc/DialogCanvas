package dialogcanvas.model;

/** Additional modifier; vanilla item modifiers are retained. */
public final class ItemAttributeDefinition extends ModelPojo {
    private String attribute;
    private Double amount;
    private String operation;
    private String slot;
    public String getAttribute() { return attribute; }
    public void setAttribute(String value) { attribute = value; }
    public Double getAmount() { return amount; }
    public void setAmount(Double value) { amount = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { operation = value; }
    public String getSlot() { return slot; }
    public void setSlot(String value) { slot = value; }
}
