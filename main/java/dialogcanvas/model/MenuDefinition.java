package dialogcanvas.model;

import java.util.List;

/** Persistent menu document, independent of Bukkit and runtime sessions. */
public final class MenuDefinition extends ModelPojo {
    private Boolean shortcutEnabled;
    public Boolean getShortcutEnabled() { return shortcutEnabled; }
    public void setShortcutEnabled(Boolean shortcutEnabled) { this.shortcutEnabled = shortcutEnabled; }

    private Integer schemaVersion;

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    private TextSpec title;

    public TextSpec getTitle() {
        return title;
    }

    public void setTitle(TextSpec title) {
        this.title = title;
    }

    private TextSpec externalTitle;

    public TextSpec getExternalTitle() {
        return externalTitle;
    }

    public void setExternalTitle(TextSpec externalTitle) {
        this.externalTitle = externalTitle;
    }

    private DialogType dialogType;

    public DialogType getDialogType() {
        return dialogType;
    }

    public void setDialogType(DialogType dialogType) {
        this.dialogType = dialogType;
    }

    private Boolean canCloseWithEscape;

    public Boolean getCanCloseWithEscape() {
        return canCloseWithEscape;
    }

    public void setCanCloseWithEscape(Boolean canCloseWithEscape) {
        this.canCloseWithEscape = canCloseWithEscape;
    }

    private AfterAction afterAction;

    public AfterAction getAfterAction() {
        return afterAction;
    }

    public void setAfterAction(AfterAction afterAction) {
        this.afterAction = afterAction;
    }

    private Boolean pause;

    public Boolean getPause() {
        return pause;
    }

    public void setPause(Boolean pause) {
        this.pause = pause;
    }

    private List<BodyBlock> body;

    public List<BodyBlock> getBody() {
        return body;
    }

    public void setBody(List<BodyBlock> body) {
        this.body = body;
    }

    private List<InputDefinition> inputs;

    public List<InputDefinition> getInputs() {
        return inputs;
    }

    public void setInputs(List<InputDefinition> inputs) {
        this.inputs = inputs;
    }

    private List<ActionDefinition> actions;

    public List<ActionDefinition> getActions() {
        return actions;
    }

    public void setActions(List<ActionDefinition> actions) {
        this.actions = actions;
    }

    private ActionDefinition noticeAction;

    public ActionDefinition getNoticeAction() {
        return noticeAction;
    }

    public void setNoticeAction(ActionDefinition noticeAction) {
        this.noticeAction = noticeAction;
    }

    private ActionDefinition yesButton;

    public ActionDefinition getYesButton() {
        return yesButton;
    }

    public void setYesButton(ActionDefinition yesButton) {
        this.yesButton = yesButton;
    }

    private ActionDefinition noButton;

    public ActionDefinition getNoButton() {
        return noButton;
    }

    public void setNoButton(ActionDefinition noButton) {
        this.noButton = noButton;
    }

    private ActionDefinition exitAction;

    public ActionDefinition getExitAction() {
        return exitAction;
    }

    public void setExitAction(ActionDefinition exitAction) {
        this.exitAction = exitAction;
    }

    private Integer columns;

    public Integer getColumns() {
        return columns;
    }

    public void setColumns(Integer columns) {
        this.columns = columns;
    }

    private Integer buttonWidth;

    public Integer getButtonWidth() {
        return buttonWidth;
    }

    public void setButtonWidth(Integer buttonWidth) {
        this.buttonWidth = buttonWidth;
    }

    private List<DialogReference> dialogs;

    public List<DialogReference> getDialogs() {
        return dialogs;
    }

    public void setDialogs(List<DialogReference> dialogs) {
        this.dialogs = dialogs;
    }

    private MenuMetadata metadata;

    public MenuMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(MenuMetadata metadata) {
        this.metadata = metadata;
    }
}
