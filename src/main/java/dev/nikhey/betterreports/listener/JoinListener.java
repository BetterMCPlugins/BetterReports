package dev.nikhey.betterreports.listener;

import dev.nikhey.betterreports.ReportService;
import dev.nikhey.betterreports.config.Settings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.function.Supplier;

public final class JoinListener implements Listener {

    private final Supplier<Settings> settings;
    private final ReportService service;

    public JoinListener(Supplier<Settings> settings, ReportService service) {
        this.settings = settings;
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (settings.get().notifyReporter()) {
            service.deliverFeedback(player);
        }
        if (settings.get().staffOnJoin() && player.hasPermission(Settings.PERM_NOTIFY)) {
            int open = service.openCount() + service.claimedCount();
            if (open > 0) {
                player.sendMessage(ReportService.prefixed("There " + (open == 1 ? "is" : "are") + " "
                        + open + " unresolved report" + (open == 1 ? "" : "s")
                        + ". Use /reports to review them.", NamedTextColor.GOLD));
            }
        }
    }
}
