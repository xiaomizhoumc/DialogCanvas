package dialogcanvas.model;


/** Optional multiline text input settings. */
public final class MultilineDefinition extends ModelPojo {
    private Integer maxLines;

    public Integer getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(Integer maxLines) {
        this.maxLines = maxLines;
    }

    private Integer height;

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }
}
