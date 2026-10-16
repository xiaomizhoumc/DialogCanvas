package dialogcanvas.model;


/** A single selectable option. */
public final class OptionDefinition extends ModelPojo {
    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    private TextSpec display;

    public TextSpec getDisplay() {
        return display;
    }

    public void setDisplay(TextSpec display) {
        this.display = display;
    }

    private Boolean initial;

    public Boolean getInitial() {
        return initial;
    }

    public void setInitial(Boolean initial) {
        this.initial = initial;
    }
}
