package dev.nikhey.betterreports.hook;

import dev.nikhey.betterreports.alert.AlertSink;
import dev.nikhey.betterreports.config.Settings;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Sends alerts through DiscordSRV instead of (or alongside) the raw webhook,
 * so servers that already run DiscordSRV need zero extra setup. The target is
 * the in-game channel named in discord.discordsrv.channel, falling back to
 * DiscordSRV's main channel.
 */
public final class DiscordSrvSink implements AlertSink {

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    public DiscordSrvSink(Supplier<Settings> settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public void send(String title, String detail, int color) {
        Settings s = settings.get();
        if (!s.discordSrvEnabled()) {
            return;
        }
        try {
            DiscordSRV srv = DiscordSRV.getPlugin();
            String channelName = s.discordSrvChannel();
            TextChannel channel = channelName.isBlank()
                    ? srv.getMainTextChannel()
                    : srv.getDestinationTextChannelForGameChannelName(channelName);
            if (channel == null) {
                return;
            }
            // Zero-width space after @ blocks accidental/abusive pings from report text.
            String safeTitle = title.replace("@", "@​");
            String safeDetail = detail.replace("\n", " | ").replace("@", "@​");
            DiscordUtil.queueMessage(channel, "**" + safeTitle + "** — " + safeDetail);
        } catch (Throwable t) {
            if (warned.compareAndSet(false, true)) {
                logger.warn("Could not deliver an alert via DiscordSRV (further failures are silent): {}",
                        t.toString());
            }
        }
    }
}
