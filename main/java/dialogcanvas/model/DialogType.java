package dialogcanvas.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum DialogType {
    @JsonProperty("notice")
    NOTICE,
    @JsonProperty("confirmation")
    CONFIRMATION,
    @JsonProperty("multi_action")
    MULTI_ACTION,
    @JsonProperty("dialog_list")
    DIALOG_LIST,
    @JsonProperty("server_links")
    SERVER_LINKS
}
