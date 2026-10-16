package dialogcanvas.web.menu;

import java.util.List;

public final class ApiException extends RuntimeException {
    public record Detail(String path, String code, String error) {}
    private final int status;
    private final String code;
    private final List<Detail> details;
    public ApiException(int status, String code, String error) { this(status, code, error, List.of()); }
    public ApiException(int status, String code, String error, List<Detail> details) {
        super(error); this.status = status; this.code = code; this.details = List.copyOf(details);
    }
    public int status() { return status; }
    public String code() { return code; }
    public List<Detail> details() { return details; }
    public static ApiException field(String path, String code, String error) {
        return new ApiException(422, "VALIDATION_FAILED", "菜单校验失败", List.of(new Detail(path, code, error)));
    }
}
