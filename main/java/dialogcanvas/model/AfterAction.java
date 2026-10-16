package dialogcanvas.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AfterAction {
    @JsonProperty("CLOSE")
    CLOSE,
    @JsonProperty("NONE")
    NONE,
    @JsonProperty("WAIT_FOR_RESPONSE")
    WAIT_FOR_RESPONSE
}
