package dialogcanvas.web;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import static org.junit.jupiter.api.Assertions.*;

class WebLoggingContextTest {
    @Test void onlyLoggingProviderDiscoveryUsesServerLoader() throws Exception {
        URL plugin = URI.create("file:/plugin/web/asset.js").toURL();
        URL provider = URI.create("file:/server/slf4j-provider").toURL();
        ClassLoader pluginLoader = resources(plugin);
        var loader = new WebLoggingContext(pluginLoader, resources(provider));
        assertEquals(provider, loader.getResources(WebLoggingContext.PROVIDERS).nextElement());
        assertEquals(plugin, loader.getResources("web/asset.js").nextElement());
        assertEquals(plugin, loader.getResources("META-INF/services/another.Service").nextElement());
        assertSame(String.class, loader.loadClass("java.lang.String"));
    }

    @Test void successfulStartupRestoresWorkerContext() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var loader = new ClassLoader(previous) {};
        WebLoggingContext.run(loader, () -> assertSame(loader, Thread.currentThread().getContextClassLoader()));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test void failedStartupRestoresWorkerContextAndPropagatesFailure() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var failure = new IllegalStateException("startup failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                WebLoggingContext.run(new ClassLoader(previous) {}, () -> { throw failure; })));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    private static ClassLoader resources(URL url) {
        return new ClassLoader(null) {
            @Override public Enumeration<URL> getResources(String name) {
                return Collections.enumeration(java.util.List.of(url));
            }
        };
    }
}
