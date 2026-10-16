package dialogcanvas.web;

import java.util.function.Consumer;

/** Console presentation shared by status and editor commands; contains no credentials. */
public final class EditorEntry {
    private EditorEntry() {}

    public static void print(EmbeddedHttpServer.Status status, Consumer<String> output) {
        if (status != null && status.state() == EmbeddedHttpServer.State.RUNNING) {
            output.accept("[DCMenu] 本机编辑器地址：" + status.address());
            output.accept("[DCMenu] 请在服务器所在电脑的浏览器中访问；若前端尚未打包，将显示说明页。");
        } else {
            output.accept("[DCMenu] " + (status == null ? "Web 服务尚未启动" : status.diagnostic()));
            output.accept("[DCMenu] 本机编辑器与 Web API 暂不可用；已加载的菜单是否可用取决于菜单服务状态，请检查启动日志。");
        }
    }
}
