package dialogcanvas.web;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

/** Keeps plugin resources visible while Javalin discovers Paper's existing SLF4J provider. */
final class WebLoggingContext extends ClassLoader {
    static final String PROVIDERS = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider";
    private final ClassLoader loggingLoader;

    WebLoggingContext(ClassLoader pluginLoader, ClassLoader loggingLoader) {
        super(pluginLoader);
        this.loggingLoader = loggingLoader;
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        return PROVIDERS.equals(name) ? loggingLoader.getResources(name) : super.getResources(name);
    }

    static void start(Runnable listenerStartup) {
        run(new WebLoggingContext(EmbeddedHttpServer.class.getClassLoader(), LoggerFactory.class.getClassLoader()), listenerStartup);
    }

    static void run(ClassLoader loader, Runnable operation) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            operation.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
