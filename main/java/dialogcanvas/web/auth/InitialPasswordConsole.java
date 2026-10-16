package dialogcanvas.web.auth;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Bypass Bukkit/System.out logging bridges so the initial password is not written to plugin logs. */
public final class InitialPasswordConsole {
    private InitialPasswordConsole() {}

    public static void print(String password) {
        String message = "DCMenu 首次初始化密码（仅此一次显示，请妥善保存）：" + password;
        java.io.Console console = System.console();
        if (console != null) {
            console.writer().println(message);
            console.writer().flush();
        } else {
            // Do not close the process-owned stdout descriptor. External shell redirection is outside our control.
            PrintStream output = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            output.println(message);
        }
    }
}
