package dev.nikhey.betterreports;

import dev.nikhey.betterreports.alert.DiscordAlerter;
import dev.nikhey.betterreports.chat.ChatBuffer;
import dev.nikhey.betterreports.command.ReportCommand;
import dev.nikhey.betterreports.command.ReportsCommand;
import dev.nikhey.betterreports.config.Settings;
import dev.nikhey.betterreports.listener.JoinListener;
import dev.nikhey.betterreports.menu.ReportsMenu;
import dev.nikhey.betterreports.storage.ReportStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BetterReportsPlugin extends JavaPlugin {

    private volatile Settings settings;
    private ReportStore store;
    private ReportService service;
    private ScheduledExecutorService maintenance;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());

        store = new ReportStore(new File(getDataFolder(), "reports.db"), getSLF4JLogger());
        try {
            store.init();
        } catch (Exception e) {
            getSLF4JLogger().error("Could not open the report database, disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ChatBuffer chat = new ChatBuffer(this::settings);
        service = new ReportService(this::settings, store, chat, getSLF4JLogger());
        service.addSink(new DiscordAlerter(this::settings, getSLF4JLogger()));
        service.refreshCounts();

        getServer().getPluginManager().registerEvents(chat, this);
        getServer().getPluginManager().registerEvents(new JoinListener(this::settings, service), this);

        ReportsMenu menu = new ReportsMenu(this, store, service);
        getServer().getPluginManager().registerEvents(menu, this);

        registerHooks();
        service.setNotesHook(dev.nikhey.betterreports.hook.NotesHook.tryResolve(getServer(), getSLF4JLogger()));

        PluginCommand report = getCommand("report");
        if (report != null) {
            ReportCommand executor = new ReportCommand(this::settings, service);
            report.setExecutor(executor);
            report.setTabCompleter(executor);
        }
        PluginCommand reports = getCommand("reports");
        if (reports != null) {
            ReportsCommand executor = new ReportsCommand(this, store, service, menu);
            reports.setExecutor(executor);
            reports.setTabCompleter(executor);
        }

        // Plain JDK scheduler keeps maintenance off server threads and Folia-safe.
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BetterReports-Maintenance");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleAtFixedRate(this::runRetention, 1, 60 * 12, TimeUnit.MINUTES);

        getSLF4JLogger().info("BetterReports enabled - evidence is frozen the moment a report is filed.");
    }

    /**
     * Optional integrations. Each hook class is only loaded (and its plugin
     * classes only touched) when the target plugin is actually installed, and
     * a failing hook is dropped with a warning instead of breaking the plugin.
     */
    private void registerHooks() {
        var pm = getServer().getPluginManager();
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            tryHook("PlaceholderAPI", "registered %betterreports_*% placeholders", () ->
                    new dev.nikhey.betterreports.hook.ReportsExpansion(this, service).register());
        }
        if (pm.isPluginEnabled("DiscordSRV")) {
            tryHook("DiscordSRV", "alerts route through its channels", () ->
                    service.addSink(new dev.nikhey.betterreports.hook.DiscordSrvSink(this::settings, getSLF4JLogger())));
        }
    }

    private void tryHook(String name, String what, Runnable registration) {
        try {
            registration.run();
            getSLF4JLogger().info("Hooked into {} - {}.", name, what);
        } catch (Throwable t) {
            getSLF4JLogger().warn("Could not enable the {} integration ({}). "
                    + "BetterReports continues without it.", name, t.toString());
        }
    }

    private void runRetention() {
        int days = settings.closedRetentionDays();
        if (days <= 0) {
            return;
        }
        store.purgeClosedOlderThan(days).whenComplete((deleted, error) -> {
            if (error != null) {
                getSLF4JLogger().warn("Retention purge failed", error);
            } else if (deleted != null && deleted > 0) {
                getSLF4JLogger().info("Retention: removed {} closed reports older than {} days", deleted, days);
            }
        });
    }

    public Settings settings() {
        return settings;
    }

    public void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig());
    }

    @Override
    public void onDisable() {
        if (maintenance != null) {
            maintenance.shutdownNow();
        }
        if (store != null) {
            store.close();
        }
    }
}
