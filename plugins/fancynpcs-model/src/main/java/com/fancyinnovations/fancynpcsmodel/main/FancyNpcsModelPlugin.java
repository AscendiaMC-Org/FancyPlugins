package com.fancyinnovations.fancynpcsmodel.main;

import com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel.FNMConfigCMD;
import com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel.FNMVersionCMD;
import com.fancyinnovations.fancynpcsmodel.commands.npc.CustomModelCMD;
import com.fancyinnovations.fancynpcsmodel.commands.npc.PlayAnimationCMD;
import com.fancyinnovations.fancynpcsmodel.config.FancyNpcsModelConfigImpl;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.PlayAnimationLoopAction;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.PlayAnimationOnceAction;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcInteractListener;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcRemoveListener;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcSpawnListener;
import com.fancyinnovations.fancynpcsmodel.listeners.PlayerJoinListener;
import com.fancyinnovations.fancynpcsmodel.listeners.ResourcePackListener;
import com.fancyinnovations.fancynpcsmodel.metrics.FNMMetrics;
import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancyanalytics.logger.LogLevel;
import de.oliver.fancyanalytics.logger.properties.ThrowableProperty;
import de.oliver.fancyanalytics.logger.appender.Appender;
import de.oliver.fancyanalytics.logger.appender.ConsoleAppender;
import de.oliver.fancyanalytics.logger.appender.JsonAppender;
import de.oliver.fancylib.VersionConfig;
import de.oliver.fancylib.logging.PluginMiddleware;
import de.oliver.fancylib.translations.Language;
import de.oliver.fancylib.translations.TextConfig;
import de.oliver.fancylib.translations.Translator;
import de.oliver.fancylib.versionFetcher.FancySpacesVersionFetcher;
import de.oliver.fancylib.versionFetcher.VersionFetcher;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class FancyNpcsModelPlugin extends JavaPlugin {

    private static FancyNpcsModelPlugin INSTANCE;
    private final ExtendedFancyLogger fancyLogger;

    private FancyNpcsModelConfigImpl fancyNpcsModelConfig;
    private VersionFetcher versionFetcher;
    private VersionConfig versionConfig;
    private Translator translator;
    private FNMMetrics metrics;

    public FancyNpcsModelPlugin() {
        INSTANCE = this;

        Appender consoleAppender = new ConsoleAppender("[{loggerName}] ({threadName}) {logLevel}: {message}");
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(System.currentTimeMillis()));
        File logsFile = new File("plugins/FancyNpcsModel/logs/FNM-logs-" + date + ".txt");
        if (!logsFile.exists()) {
            try {
                logsFile.getParentFile().mkdirs();
                logsFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        JsonAppender jsonAppender = new JsonAppender(false, false, true, logsFile.getPath());
        this.fancyLogger = new ExtendedFancyLogger(
                "FancyNpcsModel",
                LogLevel.INFO,
                List.of(consoleAppender, jsonAppender),
                List.of(new PluginMiddleware(this))
        );
    }

    public static FancyNpcsModelPlugin get() {
        return INSTANCE;
    }

    @Override
    public void onLoad() {
        fancyLogger.info("Loading FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        // Config
        fancyNpcsModelConfig = new FancyNpcsModelConfigImpl();
        fancyNpcsModelConfig.init();
        fancyNpcsModelConfig.reload();

        LogLevel logLevel;
        try {
            logLevel = LogLevel.valueOf(fancyNpcsModelConfig.getLogLevel());
        } catch (IllegalArgumentException e) {
            logLevel = LogLevel.INFO;
        }
        fancyLogger.setCurrentLevel(logLevel);

        // Version checking
        versionFetcher = new FancySpacesVersionFetcher("FancyNpcsModel");
        versionConfig = new VersionConfig(this, versionFetcher);
        versionConfig.load();

        // Translator
        registerTranslator();

        // Metrics
        metrics = new FNMMetrics();

        fancyLogger.info("Successfully loaded FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    @Override
    public void onEnable() {
        fancyLogger.info("Enabling FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        if (!fancyNpcsModelConfig.areVersionNotificationsMuted()) {
            checkForNewerVersion();
        }
        if (versionConfig.isDevelopmentBuild()) {
            fancyLogger.warn("""
                    
                    --------------------------------------------------
                    You are using a development build of FancyNpcsModel.
                    Please be aware that there might be bugs in this version.
                    If you find any bugs, please report them on our discord server (https://discord.gg/ZUgYCEJUEx).
                    Read more about the risks of using a development build here: https://fancyinnovations.com/docs/general/development-guidelines/versioning#build
                    --------------------------------------------------
                    """);
        }

        Bukkit.getServer().getGlobalRegionScheduler().runDelayed(this, (_) -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) {
                fancyLogger.error("""
                        
                        --------------------------------------------------
                        The FancyNpcs plugin is required for FancyNpcsModel to work properly.
                        Please install the FancyNpcs plugin and restart the server.
                        You can download the plugin here: https://modrinth.com/plugin/fancynpcs/versions?c=release
                        --------------------------------------------------
                        """);
                Bukkit.getPluginManager().disablePlugin(this);
            }
        }, 20L * 20); // 20s

        registerCommands();

        registerListeners();

        FancyNpcsPlugin.get().getAttributeManager().registerAttribute(CustomModelAttribute.getModelAttribute());
        FancyNpcsPlugin.get().getActionManager().registerAction(new PlayAnimationOnceAction());
        FancyNpcsPlugin.get().getActionManager().registerAction(new PlayAnimationLoopAction());
        CustomModelAttribute.registerTrackerCreationListener(this);

        // Self-heals model visibility independently of any Bukkit event - see
        // CustomModelAttribute#reconcileVisibility for why this is needed on Folia/CanvasMC.
        // Wrapped defensively: an uncaught exception here would silently stop this repeating async
        // task from ever firing again (see reconcileVisibility's own javadoc), permanently killing
        // the self-heal for every NPC, not just the one that caused it.
        Bukkit.getAsyncScheduler().runAtFixedRate(this, (_) -> {
            try {
                CustomModelAttribute.reconcileVisibility();
            } catch (Throwable t) {
                fancyLogger.error("Failed to run model visibility reconciliation", ThrowableProperty.of(t));
            }
        }, 1L, 1L, TimeUnit.SECONDS);

        metrics.register();
        metrics.checkIfPluginVersionUpdated();

        fancyLogger.info("Successfully enabled FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        fancyLogger.info("Disabling FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        // Only close trackers when the plugin is being disabled on its own (e.g. a plugin
        // reload) - the server keeps running and stale trackers would otherwise linger in the
        // world. When the whole server is stopping, Folia has already torn its regions down by
        // the time onDisable runs, so both our own region-scheduler dispatch and BetterModel's
        // internal one (Tracker#close -> HitBoxImpl#removeHitBox) are guaranteed to fail - and
        // there's nothing to clean up anyway, since every entity is about to be destroyed with
        // the server itself.
        if (!Bukkit.isStopping()) {
            for (Npc npc : FancyNpcsPlugin.get().getNpcManager().getAllNpcs()) {
                if (CustomModelAttribute.hasAttribute(npc)) {
                    CustomModelAttribute.closeAllTrackers(npc);
                }
            }
        }

        fancyLogger.info("Successfully disabled FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    private void registerCommands() {
        // fancynpcsmodel commands
        FancyNpcsPlugin.get().registerCommand(FNMConfigCMD.INSTANCE);
        FancyNpcsPlugin.get().registerCommand(FNMVersionCMD.INSTANCE);

        // npc commands
        FancyNpcsPlugin.get().registerCommand(CustomModelCMD.INSTANCE);
        FancyNpcsPlugin.get().registerCommand(PlayAnimationCMD.INSTANCE);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new NpcInteractListener(), this);
        Bukkit.getPluginManager().registerEvents(new NpcRemoveListener(), this);
        Bukkit.getPluginManager().registerEvents(new NpcSpawnListener(), this);
        Bukkit.getPluginManager().registerEvents(new ResourcePackListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this);
    }

    public void registerTranslator() {
        translator = new Translator(
                new TextConfig(
                        "#ffcc24", // color to highlight important information
                        "gray", // text color for regular messages
                        "#81E366",
                        "#E3CA66",
                        "#E36666",
                        "<color:#ba8813>[</color><gradient:#ffae00:#fffb00:#ffae00>FancyNpcsModel</gradient><color:#ba8813>]</color> <gray>"
                )
        );

        translator.loadLanguages(getDataFolder().getAbsolutePath());
        Language selectedLanguage = translator.getLanguages().stream()
                .filter(language -> language.getLanguageName().equals(fancyNpcsModelConfig.getLanguage()))
                .findFirst()
                .orElse(translator.getFallbackLanguage());
        translator.setSelectedLanguage(selectedLanguage);
    }

    private void checkForNewerVersion() {
        final var current = new ComparableVersion(versionConfig.getVersion());

        supplyAsync(getVersionFetcher()::fetchNewestVersion).thenApply(Objects::requireNonNull).whenComplete((newest, error) -> {
            if (error != null || newest.compareTo(current) <= 0) {
                return; // could not get the newest version or already on latest
            }

            fancyLogger.warn("""
                    
                    -------------------------------------------------------
                    You are not using the latest version of the FancyNpcsModel plugin.
                    Please update to the newest version (%s).
                    %s
                    -------------------------------------------------------
                    """.formatted(newest, getVersionFetcher().getDownloadUrl()));
        });
    }

    public ExtendedFancyLogger getFancyLogger() {
        return fancyLogger;
    }

    public FancyNpcsModelConfigImpl getFancyNpcsModelConfig() {
        return fancyNpcsModelConfig;
    }

    public VersionFetcher getVersionFetcher() {
        return versionFetcher;
    }

    public VersionConfig getVersionConfig() {
        return versionConfig;
    }

    public Translator getTranslator() {
        return translator;
    }

}
