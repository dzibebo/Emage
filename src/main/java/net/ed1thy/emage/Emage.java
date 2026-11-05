package net.ed1thy.emage;

import net.ed1thy.emage.command.CommandRegistry;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.config.MessageManager;
import net.ed1thy.emage.listener.ChunkTrackerListener;
import net.ed1thy.emage.listener.FrameInteractListener;
import net.ed1thy.emage.listener.PersistenceListener;
import net.ed1thy.emage.network.ImageDownloader;
import net.ed1thy.emage.network.DnsResolver;
import net.ed1thy.emage.network.EHttpClient;
import net.ed1thy.emage.processing.ColorPalette;
import net.ed1thy.emage.processing.ImagePipeline;
import net.ed1thy.emage.render.ChunkViewerTracker;
import net.ed1thy.emage.render.PacketSender;
import net.ed1thy.emage.render.RenderManager;
import net.ed1thy.emage.storage.DatabaseManager;
import net.ed1thy.emage.storage.FlatFileStorage;
import net.ed1thy.emage.storage.MapMetadataRepository;
import net.ed1thy.emage.storage.SchemaInitializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

public class Emage extends JavaPlugin {

    private DatabaseManager databaseManager;
    private RenderManager renderManager;
    private FlatFileStorage flatFileStorage;
    private MapMetadataRepository mapMetadataRepository;
    private ColorPalette colorLUT;
    private ImagePipeline imagePipeline;
    private CommandRegistry commandRegistry;

    private ExecutorService virtualThreadExecutor;
    private ExecutorService boundedIoExecutor;
    private ScheduledExecutorService scheduledExecutor;
    private ForkJoinPool computePool;

    @Override
    public void onEnable() {
        registerImageIoPlugins();

        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.boundedIoExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
            Thread t = new Thread(r, "Emage-IO-Worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Emage-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.computePool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

        ConfigManager configManager = new ConfigManager(this);
        configManager.load();

        MessageManager messageManager = new MessageManager(configManager);
        messageManager.load();

        this.databaseManager = new DatabaseManager(this, configManager);
        new SchemaInitializer(databaseManager).initialize();

        this.mapMetadataRepository = new MapMetadataRepository(databaseManager, boundedIoExecutor);
        this.mapMetadataRepository.loadAllToCache();
        this.flatFileStorage = new FlatFileStorage(this, boundedIoExecutor);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                java.util.Set<Integer> activeIds = mapMetadataRepository.getAllSyncGroupIDs();

                int brokenDbEntries = 0;
                for (int id : activeIds) {
                    if (!flatFileStorage.groupExists(id)) {
                        mapMetadataRepository.deleteSyncGroup(id);
                        brokenDbEntries++;
                    }
                }

                int deletedOrphans = flatFileStorage.cleanupOrphanedFrameData(activeIds);

                if (brokenDbEntries > 0 || deletedOrphans > 0) {
                    getLogger().info(String.format("Removed %d broken DB entries and %d orphaned frame data records.", brokenDbEntries, deletedOrphans));
                }
            } catch (Exception e) {
                getLogger().warning("Failed to run Storage Sweeper: " + e.getMessage());
            }
        });

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            databaseManager.runWalCheckpoint();
        }, 20L * 60 * 60, 20L * 60 * 60);

        DnsResolver dnsChecker = new DnsResolver(configManager);
        EHttpClient httpClient = new EHttpClient(configManager, virtualThreadExecutor);
        ImageDownloader imageDownloader = new ImageDownloader(httpClient, dnsChecker, configManager);

        this.colorLUT = new ColorPalette(computePool);
        colorLUT.generateLUT().thenRun(() -> getLogger().info("Color LUT successfully generated."));

        this.imagePipeline = new ImagePipeline(colorLUT, flatFileStorage, virtualThreadExecutor, computePool);

        ChunkViewerTracker viewerTracker = new ChunkViewerTracker();
        PacketSender packetSender = new PacketSender();
        this.renderManager = new RenderManager(this, viewerTracker, packetSender, configManager, scheduledExecutor);

        renderManager.start();

        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            viewerTracker.registerPlayer(player);
        }

        ChunkTrackerListener chunkListener = new ChunkTrackerListener(this, viewerTracker, renderManager);
        FrameInteractListener interactListener = new FrameInteractListener(this, messageManager);

        getServer().getPluginManager().registerEvents(chunkListener, this);
        getServer().getPluginManager().registerEvents(interactListener, this);
        PersistenceListener persistenceListener = new PersistenceListener(this, interactListener, mapMetadataRepository, renderManager, chunkListener, configManager, flatFileStorage, virtualThreadExecutor);
        getServer().getPluginManager().registerEvents(persistenceListener, this);

        getServer().getScheduler().runTask(this, persistenceListener::restoreLoadedChunks);

        this.commandRegistry = new CommandRegistry(
                this, configManager, messageManager, imageDownloader, mapMetadataRepository,
                imagePipeline, flatFileStorage, renderManager, packetSender, chunkListener,
                interactListener, virtualThreadExecutor
        );
        commandRegistry.registerCommands();

        preloadClasses();
        getLogger().info("Emage enabled!");
    }

    private void registerImageIoPlugins() {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        try {
            current.setContextClassLoader(getClass().getClassLoader());
            javax.imageio.ImageIO.scanForPlugins();
        } catch (Throwable t) {
            getLogger().warning("ImageIO plugin scan failed: " + t.getMessage());
        } finally {
            current.setContextClassLoader(previous);
        }
        try {
            javax.imageio.spi.IIORegistry registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
            if (!registry.getServiceProviders(com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi.class, true).hasNext()) {
                registry.registerServiceProvider(new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
            }
        } catch (Throwable t) {
            getLogger().warning("WebP reader registration failed: " + t.getMessage());
        }
    }

    /**
     * Eagerly loads every class from the plugin jar so that replacing the jar file
     * while the server is running cannot cause NoClassDefFoundError later on.
     */
    private void preloadClasses() {
        try {
            java.net.URL loc = getClass().getProtectionDomain().getCodeSource().getLocation();
            java.io.File file = new java.io.File(loc.toURI());
            if (!file.isFile()) return;
            ClassLoader loader = getClass().getClassLoader();
            Thread t = new Thread(() -> {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                        String cn = name.substring(0, name.length() - 6).replace('/', '.');
                        try {
                            Class.forName(cn, false, loader);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }, "Emage-Preload");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDisable() {
        getLogger().info("Emage shutting down..");

        if (commandRegistry != null) commandRegistry.shutdown();
        if (imagePipeline != null) imagePipeline.shutdown();
        if (colorLUT != null) colorLUT.shutdown();

        if (renderManager != null) {
            renderManager.shutdown();
        }

        if (mapMetadataRepository != null) mapMetadataRepository.shutdown();
        if (flatFileStorage != null) flatFileStorage.shutdown();

        if (scheduledExecutor != null) scheduledExecutor.shutdownNow();
        if (boundedIoExecutor != null) boundedIoExecutor.shutdownNow();
        if (virtualThreadExecutor != null) virtualThreadExecutor.shutdownNow();
        if (computePool != null) computePool.shutdownNow();

        if (databaseManager != null) {
            databaseManager.closePool();
        }
    }
}