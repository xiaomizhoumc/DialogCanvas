package dialogcanvas.model;


/** Quota configuration; player/server counts and reset policy are validated separately. */
public final class LimitDefinition extends ModelPojo {
    /** Optional menu-local quota identity shared by multiple buttons/input methods. */
    private String scope;

    public String getScope() { return scope; }

    public void setScope(String scope) { this.scope = scope; }

    private Integer player;

    public Integer getPlayer() {
        return player;
    }

    public void setPlayer(Integer player) {
        this.player = player;
    }

    private Integer server;

    public Integer getServer() {
        return server;
    }

    public void setServer(Integer server) {
        this.server = server;
    }

    private String reset;

    /** Seconds in a TIMED reset window; required and positive for TIMED. */
    private Long timedSeconds;

    public Long getTimedSeconds() { return timedSeconds; }

    public void setTimedSeconds(Long timedSeconds) { this.timedSeconds = timedSeconds; }

    public String getReset() {
        return reset;
    }

    public void setReset(String reset) {
        this.reset = reset;
    }
}
