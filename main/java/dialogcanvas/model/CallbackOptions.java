package dialogcanvas.model;


/** Declarative callback policy; lifetime is a positive ISO-8601 Duration. */
public final class CallbackOptions extends ModelPojo {
    private Integer uses;

    public Integer getUses() {
        return uses;
    }

    public void setUses(Integer uses) {
        this.uses = uses;
    }

    private String lifetime;

    public String getLifetime() {
        return lifetime;
    }

    public void setLifetime(String lifetime) {
        this.lifetime = lifetime;
    }
}
