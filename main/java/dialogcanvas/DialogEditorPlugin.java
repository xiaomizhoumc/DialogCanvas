package dialogcanvas;

import dialogcanvas.config.PluginConfig;
import dialogcanvas.dialog.DialogRenderer;
import dialogcanvas.dialog.SavedMenuOpener;
import dialogcanvas.dialog.MainMenuShortcut;
import dialogcanvas.engine.production.ProductionActionEngine;
import dialogcanvas.engine.production.ProductionStore;
import dialogcanvas.engine.limit.LimitService;
import dialogcanvas.engine.action.ActionServices;
import dialogcanvas.placeholder.PlaceholderApiResolver;
import dialogcanvas.engine.condition.PlaceholderResolver;
import dialogcanvas.scheduler.Scheduler;
import dialogcanvas.storage.FileStorage;
import dialogcanvas.web.menu.MenuEnvironment;
import dialogcanvas.web.menu.MenuRepository;
import dialogcanvas.web.menu.MenuValidator;
import dialogcanvas.web.EmbeddedHttpServer;
import dialogcanvas.command.ManagementCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plugin entry point. Owns configuration, storage, HTTP and scheduled tasks. */
public final class DialogEditorPlugin extends JavaPlugin {

    private Logger logger;
    private PluginConfig pluginConfig;
    private Scheduler scheduler;
    private PlaceholderResolver placeholderResolver;
    private DialogRenderer dialogRenderer;
    private volatile EmbeddedHttpServer httpServer;
    private MainMenuShortcut mainMenuShortcut;
    private ProductionActionEngine actionEngine;
    private ProductionStore productionStore;
    private dialogcanvas.engine.retention.PlayerDataRetention dataRetention;
    private volatile boolean startupCompleted;
    private ExecutorService initialization;
    private dialogcanvas.audit.AuditLogger audit;

    @Override
    public void onEnable() {
        logger = getLogger();
        logger.info("Starting DialogCanvas (DCMenu)...");

        try {
            audit = new dialogcanvas.audit.AuditLogger(getDataFolder().toPath(), logger);
            scheduler = new Scheduler(this);
            placeholderResolver = new PlaceholderApiResolver(logger);
            registerManagementCommand();
            Path pluginDirectory = getDataFolder().toPath();
            MenuEnvironment capabilities = MenuEnvironment.capture();
            Scheduler startupScheduler = scheduler;
            initialization = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("DCMenu-initialization").factory());
            CompletableFuture.supplyAsync(() -> {
                try {
                    PluginConfig loaded = PluginConfig.load(pluginDirectory);
                    try {
                        var examples = dialogcanvas.storage.BundledExamples.install(pluginDirectory);
                        if (examples.created() > 0) logger.info("已生成中文注释教学文件：plugins/DialogCanvas/examples/（"
                                + examples.created() + " 个新文件）。请先阅读使用说明，再按需导入菜单。");
                        if (!examples.failed().isEmpty()) logger.warning("教学示例未全部生成（EXAMPLE_INSTALL_FAILED）："
                                + String.join("、", examples.failed()) + "；已有文件已保留，插件其他功能继续初始化。");
                    } catch (java.io.IOException exampleFailure) {
                        logger.warning("教学示例生成失败（EXAMPLE_INSTALL_FAILED），请检查 examples 目录的访问权限；插件其他功能继续初始化。");
                    }
                    dialogcanvas.dialog.icon.VanillaIconResolver.mappings(); // Bundled catalog I/O before main-thread renderer construction.
                    return loaded;
                }
                catch (Exception failure) { throw new CompletionException(failure); }
            }, initialization).thenCompose(config -> startupScheduler.sync(() ->
                    initializeServices(config, pluginDirectory, capabilities, startupScheduler)))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            logger.log(Level.WARNING, "DialogCanvas initialization did not complete.", failure);
                            startupScheduler.sync(() -> getServer().getPluginManager().disablePlugin(this));
                        }
                    });
        } catch (Exception failure) {
            logger.log(Level.SEVERE, "DialogCanvas startup failed; disabling plugin.", failure);
            releaseResources();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Called exclusively by the captured startup Scheduler; no configuration I/O here. */
    private void initializeServices(PluginConfig config, Path pluginDirectory,
                                    MenuEnvironment capabilities, Scheduler startupScheduler) {
        pluginConfig = config;
        productionStore = new ProductionStore(pluginConfig.dataDirectory());
        LimitService limits = new LimitService(pluginDirectory, startupScheduler);
        dataRetention = new dialogcanvas.engine.retention.PlayerDataRetention(pluginDirectory, config.dataDirectory(), limits, productionStore, config.retention(), logger);
        dataRetention.auditLogger(audit);
        actionEngine = new ProductionActionEngine(startupScheduler, productionStore, limits, placeholderResolver,
                new ActionServices.Menus() {
                    public java.util.concurrent.CompletableFuture<Void> open(org.bukkit.entity.Player player, String id) { return openSavedMenu(player, id); }
                    public java.util.concurrent.CompletableFuture<Void> refresh(org.bukkit.entity.Player player, dialogcanvas.model.MenuDefinition menu) { return openSavedMenu(player, menu.getId()); }
                    public java.util.concurrent.CompletableFuture<Void> close(org.bukkit.entity.Player player) {
                        if (dialogRenderer != null) dialogRenderer.invalidate(player);
                        player.closeDialog();
                        return CompletableFuture.completedFuture(null);
                    }
                }, logger);
        dialogRenderer = new DialogRenderer(startupScheduler, actionEngine, logger, definition -> {
            EmbeddedHttpServer web = httpServer;
            return web == null ? () -> false : web.menus().callbackVersion(definition);
        });
        getServer().getPluginManager().registerEvents(dialogRenderer, this);

        mainMenuShortcut = new MainMenuShortcut(startupScheduler, () -> httpServer == null ? null : httpServer.menus(), this::openSavedMenu);
        getServer().getPluginManager().registerEvents(mainMenuShortcut, this);
        MenuValidator validator = new MenuValidator(capabilities);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void registered(org.bukkit.event.server.ServiceRegisterEvent event) { refreshEconomy(); }
            @org.bukkit.event.EventHandler
            public void unregistered(org.bukkit.event.server.ServiceUnregisterEvent event) { refreshEconomy(); }
            private void refreshEconomy() {
                startupScheduler.sync(() -> {
                    if (actionEngine == null) return;
                    String provider = actionEngine.economyName();
                    if (!provider.equals(validator.economyProvider())) {
                        validator.economyProvider(provider);
                        logger.info("Vault 经济提供者已更新：" + provider);
                    }
                });
            }
        }, this);
        MenuRepository menus = new MenuRepository(new FileStorage(config.dataDirectory(), pluginDirectory.resolve("menus")), validator);
        menus.auditLogger(audit);
        dataRetention.policyGuard(menus::protectLimitRetention);
        EmbeddedHttpServer startupHttp = new EmbeddedHttpServer(config, pluginDirectory, menus);
        startupHttp.auditLogger(audit);
        httpServer = startupHttp;
        startupCompleted = true;
        logger.info("DialogCanvas game commands ready; Web service is initializing.");
        ProductionActionEngine startupActions = actionEngine;
        productionStore.initialize().thenCompose(ignored -> startupScheduler.sync(() -> {
            validator.productionConnected(true);
            validator.economyProvider(startupActions.economyName());
            logger.info("游戏动作已接入；Vault 经济提供者：" + validator.economyProvider() + "；PlaceholderAPI：" + getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"));
        })).thenCompose(ignored -> startupHttp.startAsync()).whenComplete((ignored, failure) -> startupScheduler.sync(() -> {
            if (dataRetention != null && menus.ready()) dataRetention.start();
            if (failure != null) {
                logger.severe(startupHttp.status().diagnostic()
                        + "。本机编辑器和 Web API 不可用；插件保持启用，菜单能否打开取决于正式菜单服务是否已就绪。");
            } else {
                logger.info("DialogCanvas Web service started: " + startupHttp.status().address());
            }
        }));
    }

    @Override
    public void onDisable() {
        if (logger == null) {
            logger = getLogger();
        }
        logger.info("Stopping DialogCanvas...");
        boolean cleanShutdown = releaseResources();
        startupCompleted = false;
        pluginConfig = null;
        placeholderResolver = null;

        if (cleanShutdown) {
            logger.info("DialogCanvas stopped; background resources released.");
        } else {
            logger.warning("DialogCanvas shutdown finished with errors; check the logs above.");
        }
    }

    private void registerManagementCommand() {
        Scheduler commandScheduler = scheduler;
        var command = new ManagementCommand(commandScheduler,
                        () -> httpServer == null ? null : httpServer.menus(),
                        () -> httpServer == null ? null : httpServer.status(),
                        () -> startupCompleted,
                        this::openSavedMenu, logger, () -> dataRetention);
        command.auditLogger(audit);
        // JavaPlugin.registerCommand itself delegates to this lifecycle. Check aliases at actual registration,
        // after vanilla/earlier plugin commands are visible, including Brigadier-only command labels.
        getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
            if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Register DCMenu commands on main thread");
            var registrar = event.registrar();
            var aliases = ManagementCommand.registrationAliases(label -> registrar.getDispatcher().getRoot().getChild(label) != null
                    || getServer().getCommandMap().getCommand(label) != null, logger::warning);
            registrar.register("dcmenu", "DialogCanvas 菜单管理：help、editor/edit、main、list [页码]、open <菜单ID> [在线玩家]、reload [菜单ID]、data", aliases, command);
        });
    }
    private java.util.concurrent.CompletableFuture<Void> openSavedMenu(org.bukkit.entity.Player player, String id) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Open saved menu through Scheduler.sync");
        if (!startupCompleted || scheduler == null || httpServer == null || dialogRenderer == null)
            return CompletableFuture.failedFuture(new IllegalStateException("DialogCanvas is stopping or not ready"));
        return new SavedMenuOpener(scheduler, httpServer.menus()::definition, dialogRenderer::show, logger).open(player, id);
    }

    public DialogRenderer getDialogRenderer() {
        DialogRenderer renderer = dialogRenderer;
        if (!startupCompleted || renderer == null) {
            throw new IllegalStateException("DialogCanvas is not running");
        }
        return renderer;
    }

    public PlaceholderResolver getPlaceholderResolver() {
        PlaceholderResolver resolver = placeholderResolver;
        if (!startupCompleted || resolver == null) throw new IllegalStateException("DialogCanvas is not running");
        return resolver;
    }

    public MenuRepository getMenuRepository() {
        EmbeddedHttpServer web = httpServer;
        if (!startupCompleted || web == null) throw new IllegalStateException("DialogCanvas is not running");
        return web.menus();
    }

    /** Each cleanup step runs even if a previous step fails. Safe to call repeatedly. */
    private boolean releaseResources() {
        boolean success = true;
        java.util.ArrayList<CompletableFuture<?>> cleanup = new java.util.ArrayList<>();
        startupCompleted = false;
        ExecutorService starting = initialization;
        if (starting != null) {
            starting.shutdown(); initialization = null;
            cleanup.add(CompletableFuture.runAsync(() -> {
                try { while (!starting.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) { /* drain configuration I/O */ } }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new CompletionException(failure); }
            }));
        }
        if (mainMenuShortcut != null) { mainMenuShortcut.close(); mainMenuShortcut = null; }
        if (dialogRenderer != null) dialogRenderer.close();
        if (dataRetention != null) { cleanup.add(dataRetention.closeAsync()); dataRetention = null; }
        if (actionEngine != null) {
            cleanup.add(actionEngine.closeAsync().whenComplete((ignored, failure) -> {
                if (failure != null) logger.log(Level.SEVERE, "Transaction shutdown failed; check durable records.", failure);
            }));
            actionEngine = null;
        } else if (productionStore != null) { cleanup.add(productionStore.closeAsync()); }
        productionStore = null;
        dialogRenderer = null;

        // Reject new game tasks and cancel queued tasks before closing storage.
        if (scheduler != null) {
            try {
                scheduler.close();
            } catch (Exception failure) {
                success = false;
                logger.log(Level.SEVERE, "Failed to release scheduled tasks.", failure);
            } finally {
                scheduler = null;
            }
        }

        // Stop HTTP; its repository drains any in-progress commit and closes storage asynchronously.
        if (httpServer != null) {
            try {
                cleanup.add(httpServer.stopAsync().whenComplete((ignored, failure) -> {
                    if (failure != null) logger.log(Level.SEVERE, "Failed to stop the HTTP service.", failure);
                    else logger.info("DialogCanvas Web service stopped; port released.");
                }));
            } catch (Exception failure) {
                success = false;
                logger.log(Level.SEVERE, "Failed to stop the HTTP service.", failure);
            } finally {
                httpServer = null;
            }
        }

        // Paper closes this JAR classloader immediately after onDisable returns.
        // User-approved lifecycle exception: only here may the main thread wait,
        // bounded to 30s, after Scheduler cutoff. All I/O/Jetty work remains off-main.
        if (audit != null) {
            var stoppingAudit = audit; audit = null;
            // Producers drain first, even if one fails; accepted audit events are drained last.
            var producers = CompletableFuture.allOf(cleanup.toArray(CompletableFuture[]::new));
            cleanup.add(producers.handle((ignored, failure) -> null).thenCompose(ignored -> stoppingAudit.closeAsync()));
        }
        if (starting != null || !cleanup.isEmpty()) {
            try {
                CompletableFuture.allOf(cleanup.toArray(CompletableFuture[]::new)).get(30, java.util.concurrent.TimeUnit.SECONDS);
                logger.info("DialogCanvas background resources released.");
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                logger.log(Level.SEVERE, "Background shutdown incomplete; check durable transaction/menu records.", failure);
                success = false;
            }
        }

        return success;
    }
}
