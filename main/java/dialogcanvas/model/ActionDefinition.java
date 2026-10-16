package dialogcanvas.model;

import java.util.List;
import java.util.Map;

/** Dialog button plus plugin business rules; it is not an executable callback. */
public final class ActionDefinition extends ModelPojo {
    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private TextSpec label;
    private ButtonIconDefinition icon;

    public ButtonIconDefinition getIcon() { return icon; }
    public void setIcon(ButtonIconDefinition icon) { this.icon = icon; }

    public TextSpec getLabel() {
        return label;
    }

    public void setLabel(TextSpec label) {
        this.label = label;
    }

    private TextSpec tooltip;

    public TextSpec getTooltip() {
        return tooltip;
    }

    public void setTooltip(TextSpec tooltip) {
        this.tooltip = tooltip;
    }

    private Integer width;

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    private String actionType;

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    private String actionId;

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    private String additions;

    public String getAdditions() {
        return additions;
    }

    public void setAdditions(String additions) {
        this.additions = additions;
    }

    private String template;

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    private StaticClickDefinition clickEvent;

    public StaticClickDefinition getClickEvent() {
        return clickEvent;
    }

    public void setClickEvent(StaticClickDefinition clickEvent) {
        this.clickEvent = clickEvent;
    }

    private CallbackOptions callbackOptions;

    public CallbackOptions getCallbackOptions() {
        return callbackOptions;
    }

    public void setCallbackOptions(CallbackOptions callbackOptions) {
        this.callbackOptions = callbackOptions;
    }

    private ConditionDefinition conditions;

    public ConditionDefinition getConditions() {
        return conditions;
    }

    public void setConditions(ConditionDefinition conditions) {
        this.conditions = conditions;
    }

    private List<ActionStepDefinition> actions;

    public List<ActionStepDefinition> getActions() {
        return actions;
    }

    public void setActions(List<ActionStepDefinition> actions) {
        this.actions = actions;
    }

    private List<ActionStepDefinition> denyActions;

    public List<ActionStepDefinition> getDenyActions() {
        return denyActions;
    }

    public void setDenyActions(List<ActionStepDefinition> denyActions) {
        this.denyActions = denyActions;
    }

    private Map<String, LimitDefinition> limit;

    public Map<String, LimitDefinition> getLimit() {
        return limit;
    }

    public void setLimit(Map<String, LimitDefinition> limit) {
        this.limit = limit;
    }

    private Map<String, Double> cooldown;

    public Map<String, Double> getCooldown() {
        return cooldown;
    }

    public void setCooldown(Map<String, Double> cooldown) {
        this.cooldown = cooldown;
    }

    private Boolean refreshAfterAction;

    public Boolean getRefreshAfterAction() {
        return refreshAfterAction;
    }

    public void setRefreshAfterAction(Boolean refreshAfterAction) {
        this.refreshAfterAction = refreshAfterAction;
    }
}
