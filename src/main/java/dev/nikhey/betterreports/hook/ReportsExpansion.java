package dev.nikhey.betterreports.hook;

import dev.nikhey.betterreports.ReportService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI expansion. Placeholders:
 *   %betterreports_open%       reports waiting for a claim
 *   %betterreports_claimed%    reports currently being handled
 *   %betterreports_unresolved% open + claimed
 *
 * PlaceholderAPI calls are synchronous while the store is async, so values
 * come from the service's counter cache, refreshed on every report mutation.
 */
public final class ReportsExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final ReportService service;

    public ReportsExpansion(Plugin plugin, ReportService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public String getIdentifier() {
        return "betterreports";
    }

    @Override
    public String getAuthor() {
        return "Nikhey";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return switch (params.toLowerCase()) {
            case "open" -> String.valueOf(service.openCount());
            case "claimed" -> String.valueOf(service.claimedCount());
            case "unresolved" -> String.valueOf(service.openCount() + service.claimedCount());
            default -> null;
        };
    }
}
