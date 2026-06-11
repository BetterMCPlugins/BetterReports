package dev.nikhey.betterreports;

import dev.nikhey.betterreports.alert.AlertSink;
import dev.nikhey.betterreports.chat.ChatBuffer;
import dev.nikhey.betterreports.config.Settings;
import dev.nikhey.betterreports.model.ChatLine;
import dev.nikhey.betterreports.model.Report;
import dev.nikhey.betterreports.model.ReportStatus;
import dev.nikhey.betterreports.storage.ReportStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Report lifecycle: filing (with the evidence snapshot frozen at that moment),
 * claim/unclaim, resolve/dismiss, reporter feedback and staff alerts. All
 * storage work happens off the server threads; player messages are sent via
 * Adventure, which is thread-safe on Paper.
 */
public final class ReportService {

    public static final UUID CONSOLE_UUID = new UUID(0, 0);

    private final Supplier<Settings> settings;
    private final ReportStore store;
    private final ChatBuffer chat;
    private final Logger logger;
    private final List<AlertSink> sinks = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private volatile int openCount;
    private volatile int claimedCount;

    public ReportService(Supplier<Settings> settings, ReportStore store, ChatBuffer chat, Logger logger) {
        this.settings = settings;
        this.store = store;
        this.chat = chat;
        this.logger = logger;
    }

    public void addSink(AlertSink sink) {
        sinks.add(sink);
    }

    public void file(Player reporter, OfflinePlayer target, String reason) {
        Settings s = settings.get();
        long now = System.currentTimeMillis();
        UUID reporterId = reporter.getUniqueId();

        if (target.getUniqueId().equals(reporterId)) {
            reporter.sendMessage(prefixed("You can not report yourself.", NamedTextColor.RED));
            return;
        }
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null && onlineTarget.hasPermission(Settings.PERM_IMMUNE)) {
            reporter.sendMessage(prefixed("This player can not be reported.", NamedTextColor.RED));
            return;
        }
        boolean bypass = reporter.hasPermission(Settings.PERM_BYPASS);
        if (!bypass && s.cooldownSeconds() > 0) {
            long readyAt = lastReport.getOrDefault(reporterId, 0L) + s.cooldownSeconds() * 1000L;
            if (now < readyAt) {
                reporter.sendMessage(prefixed("Please wait " + ((readyAt - now) / 1000 + 1)
                        + "s before reporting again.", NamedTextColor.RED));
                return;
            }
        }

        // Freeze evidence immediately - by the time staff look at the report,
        // the chat buffer may long have rolled over.
        List<ChatLine> evidence = s.chatHistoryEnabled()
                ? chat.snapshot(target.getUniqueId(), reporterId)
                : List.of();
        String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();

        CompletableFuture<Integer> openByReporter = !bypass && s.maxOpenPerPlayer() > 0
                ? store.countOpenByReporter(reporterId)
                : CompletableFuture.completedFuture(0);

        openByReporter.thenCompose(count -> {
            if (!bypass && s.maxOpenPerPlayer() > 0 && count >= s.maxOpenPerPlayer()) {
                reporter.sendMessage(prefixed("You already have " + count
                        + " open reports. Please wait until staff handled them.", NamedTextColor.RED));
                return CompletableFuture.completedFuture(null);
            }
            if (s.mergeWindowMinutes() <= 0) {
                return createReport(reporter, target, targetName, reason, evidence, now);
            }
            long since = now - s.mergeWindowMinutes() * 60_000L;
            return store.findOpenByTarget(target.getUniqueId(), since).thenCompose(existing -> {
                if (existing.isPresent()) {
                    return bumpReport(reporter, existing.get(), now);
                }
                return createReport(reporter, target, targetName, reason, evidence, now);
            });
        }).exceptionally(error -> {
            logger.error("Failed to file a report", error);
            reporter.sendMessage(prefixed("Something went wrong filing your report.", NamedTextColor.RED));
            return null;
        });
    }

    private CompletableFuture<Void> createReport(Player reporter, OfflinePlayer target, String targetName,
                                                 String reason, List<ChatLine> evidence, long now) {
        Player onlineTarget = target.getPlayer();
        String world = null;
        int x = 0;
        int y = 0;
        int z = 0;
        String gamemode = null;
        int ping = 0;
        if (onlineTarget != null) {
            Location loc = onlineTarget.getLocation();
            world = loc.getWorld() != null ? loc.getWorld().getName() : null;
            x = loc.getBlockX();
            y = loc.getBlockY();
            z = loc.getBlockZ();
            gamemode = onlineTarget.getGameMode().name();
            ping = onlineTarget.getPing();
        }
        Report draft = new Report(0, now, reporter.getUniqueId(), reporter.getName(),
                target.getUniqueId(), targetName, reason, ReportStatus.OPEN,
                null, null, 0, null, null, 0, null,
                world, x, y, z, gamemode, ping, 0);
        return store.create(draft, evidence).thenAccept(id -> {
            lastReport.put(reporter.getUniqueId(), now);
            reporter.sendMessage(prefixed("Report #" + id + " against " + targetName
                    + " was filed. Thank you!", NamedTextColor.GREEN));
            notifyStaff(Component.text()
                    .append(prefix())
                    .append(Component.text("New report ", NamedTextColor.GOLD))
                    .append(Component.text("#" + id, NamedTextColor.YELLOW))
                    .append(Component.text(": " + targetName + " — " + reason
                            + " (by " + reporter.getName() + ")", NamedTextColor.GRAY))
                    .build());
            alert("New report #" + id + " — " + targetName,
                    "Reporter: " + reporter.getName() + "\nReason: " + reason
                            + (evidence.isEmpty() ? "" : "\nChat evidence: " + evidence.size() + " lines"),
                    ReportStatus.OPEN.discordColor());
            refreshCounts();
        });
    }

    private CompletableFuture<Void> bumpReport(Player reporter, Report existing, long now) {
        return store.bump(existing.id()).thenAccept(rows -> {
            lastReport.put(reporter.getUniqueId(), now);
            int total = existing.bumps() + 2; // original report + previous bumps + this one
            reporter.sendMessage(prefixed("Added your report to the existing report #" + existing.id()
                    + " against " + existing.targetName() + ".", NamedTextColor.GREEN));
            notifyStaff(Component.text()
                    .append(prefix())
                    .append(Component.text("Report ", NamedTextColor.GOLD))
                    .append(Component.text("#" + existing.id(), NamedTextColor.YELLOW))
                    .append(Component.text(" against " + existing.targetName()
                            + " was reported again (" + total + "x), latest by "
                            + reporter.getName() + ".", NamedTextColor.GRAY))
                    .build());
            alert("Report #" + existing.id() + " bumped — " + existing.targetName(),
                    "Now reported " + total + "x, latest by " + reporter.getName(),
                    ReportStatus.OPEN.discordColor());
        });
    }

    public void claim(CommandSender staff, long id) {
        store.claim(id, uuidOf(staff), nameOf(staff), System.currentTimeMillis()).thenAccept(ok -> {
            if (!ok) {
                staff.sendMessage(prefixed("Report #" + id + " is not open - already claimed or closed.",
                        NamedTextColor.RED));
                return;
            }
            staff.sendMessage(prefixed("You claimed report #" + id + ".", NamedTextColor.GREEN));
            notifyStaff(prefixed("Report #" + id + " claimed by " + nameOf(staff) + ".", NamedTextColor.GRAY));
            refreshCounts();
        }).exceptionally(error -> {
            logger.error("Failed to claim report {}", id, error);
            return null;
        });
    }

    public void unclaim(CommandSender staff, long id) {
        store.unclaim(id).thenAccept(ok -> {
            if (!ok) {
                staff.sendMessage(prefixed("Report #" + id + " is not claimed.", NamedTextColor.RED));
                return;
            }
            staff.sendMessage(prefixed("Report #" + id + " is open again.", NamedTextColor.GREEN));
            notifyStaff(prefixed("Report #" + id + " was unclaimed by " + nameOf(staff) + ".", NamedTextColor.GRAY));
            refreshCounts();
        }).exceptionally(error -> {
            logger.error("Failed to unclaim report {}", id, error);
            return null;
        });
    }

    public void close(CommandSender staff, long id, ReportStatus status, String note) {
        long now = System.currentTimeMillis();
        store.byId(id).thenCompose(found -> {
            if (found.isEmpty() || found.get().status().isClosed()) {
                staff.sendMessage(prefixed("Report #" + id + " does not exist or is already closed.",
                        NamedTextColor.RED));
                return CompletableFuture.completedFuture(null);
            }
            Report report = found.get();
            return store.close(id, status, uuidOf(staff), nameOf(staff), now, note).thenAccept(ok -> {
                if (!ok) {
                    staff.sendMessage(prefixed("Report #" + id + " was already closed.", NamedTextColor.RED));
                    return;
                }
                String verb = status == ReportStatus.RESOLVED ? "resolved" : "dismissed";
                staff.sendMessage(prefixed("Report #" + id + " " + verb + ".", NamedTextColor.GREEN));
                notifyStaff(prefixed("Report #" + id + " (" + report.targetName() + ") " + verb
                        + " by " + nameOf(staff)
                        + (note == null || note.isBlank() ? "" : ": " + note), NamedTextColor.GRAY));
                alert("Report #" + id + " " + verb + " — " + report.targetName(),
                        "Handled by " + nameOf(staff)
                                + (note == null || note.isBlank() ? "" : "\nNote: " + note),
                        status.discordColor());
                sendReporterFeedback(report, verb, nameOf(staff), now);
                refreshCounts();
            });
        }).exceptionally(error -> {
            logger.error("Failed to close report {}", id, error);
            return null;
        });
    }

    private void sendReporterFeedback(Report report, String verb, String staffName, long now) {
        if (!settings.get().notifyReporter()) {
            return;
        }
        String message = "Your report #" + report.id() + " against " + report.targetName()
                + " was " + verb + " by " + staffName + ". Thank you for reporting!";
        Player reporter = Bukkit.getPlayer(report.reporterUuid());
        if (reporter != null) {
            reporter.sendMessage(prefixed(message, NamedTextColor.GREEN));
        } else {
            store.queueFeedback(report.reporterUuid(), message, now);
        }
    }

    /** Delivers queued "your report was handled" messages, called on join. */
    public void deliverFeedback(Player player) {
        store.drainFeedback(player.getUniqueId()).thenAccept(messages -> {
            for (String message : messages) {
                player.sendMessage(prefixed(message, NamedTextColor.GREEN));
            }
        }).exceptionally(error -> {
            logger.warn("Failed to deliver reporter feedback", error);
            return null;
        });
    }

    public void refreshCounts() {
        store.openCounts().thenAccept(counts -> {
            openCount = counts[0];
            claimedCount = counts[1];
        }).exceptionally(error -> {
            logger.warn("Failed to refresh report counts", error);
            return null;
        });
    }

    public int openCount() {
        return openCount;
    }

    public int claimedCount() {
        return claimedCount;
    }

    private void notifyStaff(Component message) {
        if (!settings.get().notifyIngame()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(Settings.PERM_NOTIFY)) {
                player.sendMessage(message);
            }
        }
    }

    private void alert(String title, String detail, int color) {
        for (AlertSink sink : sinks) {
            try {
                sink.send(title, detail, color);
            } catch (Throwable t) {
                logger.warn("Alert sink failed: {}", t.toString());
            }
        }
    }

    private static UUID uuidOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_UUID;
    }

    private static String nameOf(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "Console";
    }

    public static Component prefix() {
        return Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text("Reports", NamedTextColor.AQUA))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .build();
    }

    public static Component prefixed(String message, NamedTextColor color) {
        return Component.text()
                .append(prefix())
                .append(Component.text(message, color))
                .build();
    }
}
