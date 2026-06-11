package dev.nikhey.betterreports.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class Settings {

    public static final String PERM_REPORT = "betterreports.report";
    public static final String PERM_STAFF = "betterreports.staff";
    public static final String PERM_NOTIFY = "betterreports.notify";
    public static final String PERM_ADMIN = "betterreports.admin";
    public static final String PERM_IMMUNE = "betterreports.immune";
    public static final String PERM_BYPASS = "betterreports.bypass-cooldown";

    private final boolean requireReason;
    private final int cooldownSeconds;
    private final int maxOpenPerPlayer;
    private final boolean allowOfflineTargets;
    private final int mergeWindowMinutes;
    private final List<String> reasons;
    private final boolean chatHistory;
    private final int chatLines;
    private final int snapshotLines;
    private final boolean notifyIngame;
    private final boolean staffOnJoin;
    private final boolean notifyReporter;
    private final boolean discordEnabled;
    private final String webhookUrl;
    private final boolean discordSrvEnabled;
    private final String discordSrvChannel;
    private final int closedRetentionDays;

    private Settings(FileConfiguration c) {
        this.requireReason = c.getBoolean("reports.require-reason", true);
        this.cooldownSeconds = c.getInt("reports.cooldown-seconds", 60);
        this.maxOpenPerPlayer = c.getInt("reports.max-open-per-player", 3);
        this.allowOfflineTargets = c.getBoolean("reports.allow-offline-targets", true);
        this.mergeWindowMinutes = c.getInt("reports.merge-window-minutes", 15);
        this.reasons = List.copyOf(c.getStringList("reports.reasons"));
        this.chatHistory = c.getBoolean("evidence.chat-history", true);
        this.chatLines = Math.max(1, c.getInt("evidence.chat-lines", 30));
        this.snapshotLines = Math.max(1, c.getInt("evidence.snapshot-lines", 20));
        this.notifyIngame = c.getBoolean("notify.ingame", true);
        this.staffOnJoin = c.getBoolean("notify.staff-on-join", true);
        this.notifyReporter = c.getBoolean("feedback.notify-reporter", true);
        this.discordEnabled = c.getBoolean("discord.enabled", false);
        this.webhookUrl = c.getString("discord.webhook-url", "");
        this.discordSrvEnabled = c.getBoolean("discord.discordsrv.enabled", true);
        this.discordSrvChannel = c.getString("discord.discordsrv.channel", "");
        this.closedRetentionDays = c.getInt("retention.closed-days", 60);
    }

    public static Settings load(FileConfiguration config) {
        return new Settings(config);
    }

    public boolean requireReason() {
        return requireReason;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public int maxOpenPerPlayer() {
        return maxOpenPerPlayer;
    }

    public boolean allowOfflineTargets() {
        return allowOfflineTargets;
    }

    public int mergeWindowMinutes() {
        return mergeWindowMinutes;
    }

    public List<String> reasons() {
        return reasons;
    }

    public boolean chatHistoryEnabled() {
        return chatHistory;
    }

    public int chatLines() {
        return chatLines;
    }

    public int snapshotLines() {
        return snapshotLines;
    }

    public boolean notifyIngame() {
        return notifyIngame;
    }

    public boolean staffOnJoin() {
        return staffOnJoin;
    }

    public boolean notifyReporter() {
        return notifyReporter;
    }

    public boolean discordEnabled() {
        return discordEnabled && webhookUrl != null && !webhookUrl.isBlank();
    }

    public String webhookUrl() {
        return webhookUrl;
    }

    public boolean discordSrvEnabled() {
        return discordSrvEnabled;
    }

    public String discordSrvChannel() {
        return discordSrvChannel;
    }

    public int closedRetentionDays() {
        return closedRetentionDays;
    }
}
