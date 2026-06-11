package dev.nikhey.betterreports.command;

import dev.nikhey.betterreports.BetterReportsPlugin;
import dev.nikhey.betterreports.ReportService;
import dev.nikhey.betterreports.config.Settings;
import dev.nikhey.betterreports.menu.ReportsMenu;
import dev.nikhey.betterreports.model.ChatLine;
import dev.nikhey.betterreports.model.Report;
import dev.nikhey.betterreports.model.ReportStatus;
import dev.nikhey.betterreports.storage.ReportStore;
import dev.nikhey.betterreports.util.TimeText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class ReportsCommand implements TabExecutor {

    private static final int PAGE_SIZE = 8;
    private static final List<String> SUBCOMMANDS = List.of(
            "list", "history", "view", "claim", "unclaim", "resolve", "dismiss", "tp", "stats", "purge", "reload");

    private final BetterReportsPlugin plugin;
    private final ReportStore store;
    private final ReportService service;
    private final ReportsMenu menu;

    public ReportsCommand(BetterReportsPlugin plugin, ReportStore store, ReportService service, ReportsMenu menu) {
        this.plugin = plugin;
        this.store = store;
        this.service = service;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                menu.open(player);
            } else {
                list(sender, 1, false);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, parsePage(args), false);
            case "history" -> list(sender, parsePage(args), true);
            case "view" -> withId(sender, args, this::view);
            case "claim" -> withId(sender, args, (s, id) -> service.claim(s, id));
            case "unclaim" -> withId(sender, args, (s, id) -> service.unclaim(s, id));
            case "resolve" -> withId(sender, args, (s, id) ->
                    service.close(s, id, ReportStatus.RESOLVED, note(args)));
            case "dismiss" -> withId(sender, args, (s, id) ->
                    service.close(s, id, ReportStatus.DISMISSED, note(args)));
            case "tp" -> withId(sender, args, this::teleport);
            case "stats" -> stats(sender, args.length > 1 ? args[1] : null);
            case "purge" -> purge(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(ReportService.prefixed(
                    "Usage: /" + label + " <list|history|view|claim|unclaim|resolve|dismiss|tp|stats|purge|reload>",
                    NamedTextColor.RED));
        }
        return true;
    }

    private interface IdAction {
        void run(CommandSender sender, long id);
    }

    private void withId(CommandSender sender, String[] args, IdAction action) {
        if (args.length < 2) {
            sender.sendMessage(ReportService.prefixed("Usage: /reports " + args[0] + " <id>", NamedTextColor.RED));
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[1].replace("#", ""));
        } catch (NumberFormatException e) {
            sender.sendMessage(ReportService.prefixed("'" + args[1] + "' is not a report id.", NamedTextColor.RED));
            return;
        }
        action.run(sender, id);
    }

    private static int parsePage(String[] args) {
        if (args.length < 2) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(args[1]));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String note(String[] args) {
        return args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : null;
    }

    private void list(CommandSender sender, int page, boolean history) {
        var future = history
                ? store.history(PAGE_SIZE, (page - 1) * PAGE_SIZE)
                : store.openReports(PAGE_SIZE, (page - 1) * PAGE_SIZE);
        future.whenComplete((reports, error) -> {
            if (error != null || reports == null) {
                sender.sendMessage(ReportService.prefixed("Failed to load the reports.", NamedTextColor.RED));
                return;
            }
            if (reports.isEmpty()) {
                sender.sendMessage(ReportService.prefixed(history
                        ? "No closed reports" + (page > 1 ? " on page " + page : "") + "."
                        : "No open reports - all clear!", history ? NamedTextColor.GRAY : NamedTextColor.GREEN));
                return;
            }
            sender.sendMessage(ReportService.prefixed((history ? "Closed" : "Open")
                    + " reports — page " + page, NamedTextColor.GOLD));
            for (Report report : reports) {
                sender.sendMessage(summaryLine(report));
            }
        });
    }

    private Component summaryLine(Report report) {
        String when = report.status().isClosed()
                ? TimeText.ago(report.closeTime())
                : TimeText.ago(report.time());
        return Component.text()
                .append(Component.text(" #" + report.id() + " ", NamedTextColor.YELLOW))
                .append(Component.text("[" + report.status().display() + "] ", report.status().color()))
                .append(Component.text(report.targetName(), NamedTextColor.WHITE))
                .append(Component.text(" — " + report.reason(), NamedTextColor.GRAY))
                .append(Component.text(" (" + when
                        + (report.bumps() > 0 ? ", " + (report.bumps() + 1) + "x" : "") + ")",
                        NamedTextColor.DARK_GRAY))
                .build()
                .clickEvent(ClickEvent.runCommand("/reports view " + report.id()))
                .hoverEvent(Component.text("Click to view report #" + report.id(), NamedTextColor.AQUA));
    }

    private void view(CommandSender sender, long id) {
        store.byId(id).thenCombine(store.evidence(id), (found, evidence) -> {
            if (found.isEmpty()) {
                sender.sendMessage(ReportService.prefixed("Report #" + id + " does not exist.", NamedTextColor.RED));
                return null;
            }
            Report r = found.get();
            sender.sendMessage(ReportService.prefixed("Report #" + r.id() + " — " + r.targetName(),
                    NamedTextColor.GOLD));
            sender.sendMessage(field("Status", r.status().display()));
            sender.sendMessage(field("Reason", r.reason()));
            sender.sendMessage(field("Reporter", r.reporterName()));
            sender.sendMessage(field("Filed", TimeText.absolute(r.time()) + " (" + TimeText.ago(r.time()) + ")"));
            if (r.bumps() > 0) {
                sender.sendMessage(field("Reported", (r.bumps() + 1) + "x"));
            }
            if (r.world() != null) {
                sender.sendMessage(field("Location", r.world() + " " + r.x() + ", " + r.y() + ", " + r.z()));
            }
            if (r.gamemode() != null) {
                sender.sendMessage(field("Gamemode/ping then", r.gamemode() + " / " + r.ping() + "ms"));
            }
            if (r.claimerName() != null) {
                sender.sendMessage(field("Claimed by", r.claimerName()));
            }
            if (r.status().isClosed()) {
                sender.sendMessage(field("Closed by", r.closerName() + " at " + TimeText.absolute(r.closeTime())));
                if (r.closeNote() != null && !r.closeNote().isBlank()) {
                    sender.sendMessage(field("Note", r.closeNote()));
                }
            }
            if (!evidence.isEmpty()) {
                sender.sendMessage(field("Chat evidence", evidence.size() + " lines"));
                for (ChatLine chatLine : evidence) {
                    sender.sendMessage(Component.text()
                            .append(Component.text("  " + TimeText.clock(chatLine.time()) + " ",
                                    NamedTextColor.DARK_GRAY))
                            .append(Component.text(chatLine.speakerName() + ": ", NamedTextColor.YELLOW))
                            .append(Component.text(chatLine.message(), NamedTextColor.WHITE))
                            .build());
                }
            }
            return null;
        }).exceptionally(error -> {
            sender.sendMessage(ReportService.prefixed("Failed to load report #" + id + ".", NamedTextColor.RED));
            return null;
        });
    }

    private static Component field(String label, String value) {
        return Component.text()
                .append(Component.text(" " + label + ": ", NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.WHITE))
                .build();
    }

    private void teleport(CommandSender sender, long id) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ReportService.prefixed("Only players can teleport.", NamedTextColor.RED));
            return;
        }
        store.byId(id).thenAccept(found -> {
            if (found.isEmpty() || found.get().world() == null) {
                player.sendMessage(ReportService.prefixed("Report #" + id
                        + " has no stored location.", NamedTextColor.RED));
                return;
            }
            menu.teleport(player, found.get());
        });
    }

    private void stats(CommandSender sender, String staffName) {
        if (staffName == null) {
            store.stats().whenComplete((stats, error) -> {
                if (error != null || stats == null) {
                    sender.sendMessage(ReportService.prefixed("Failed to load stats.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(ReportService.prefixed("Report stats", NamedTextColor.GOLD));
                sender.sendMessage(field("Total", String.valueOf(stats.total())));
                sender.sendMessage(field("Open / claimed", stats.open() + " / " + stats.claimed()));
                sender.sendMessage(field("Resolved / dismissed", stats.resolved() + " / " + stats.dismissed()));
                sender.sendMessage(field("Avg time to claim", TimeText.duration(stats.avgClaimMillis())));
                sender.sendMessage(field("Avg time to close", TimeText.duration(stats.avgCloseMillis())));
            });
            return;
        }
        store.staffStats(staffName).whenComplete((stats, error) -> {
            if (error != null || stats == null) {
                sender.sendMessage(ReportService.prefixed("Failed to load stats.", NamedTextColor.RED));
                return;
            }
            sender.sendMessage(ReportService.prefixed("Report stats — " + staffName, NamedTextColor.GOLD));
            sender.sendMessage(field("Claimed", String.valueOf(stats.claimed())));
            sender.sendMessage(field("Resolved / dismissed", stats.resolved() + " / " + stats.dismissed()));
            sender.sendMessage(field("Avg time to claim", TimeText.duration(stats.avgClaimMillis())));
            sender.sendMessage(field("Avg time to close", TimeText.duration(stats.avgCloseMillis())));
        });
    }

    private void purge(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Settings.PERM_ADMIN)) {
            sender.sendMessage(ReportService.prefixed("You may not purge reports.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ReportService.prefixed("Usage: /reports purge <days> - deletes closed reports older than <days>.",
                    NamedTextColor.RED));
            return;
        }
        int days;
        try {
            days = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ReportService.prefixed("'" + args[1] + "' is not a number of days.", NamedTextColor.RED));
            return;
        }
        store.purgeClosedOlderThan(days).whenComplete((deleted, error) -> {
            if (error != null) {
                sender.sendMessage(ReportService.prefixed("Purge failed.", NamedTextColor.RED));
                return;
            }
            sender.sendMessage(ReportService.prefixed("Deleted " + deleted + " closed report"
                    + (deleted == 1 ? "" : "s") + " older than " + days + " days.", NamedTextColor.GREEN));
        });
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(Settings.PERM_ADMIN)) {
            sender.sendMessage(ReportService.prefixed("You may not reload the config.", NamedTextColor.RED));
            return;
        }
        plugin.reloadSettings();
        sender.sendMessage(ReportService.prefixed("Configuration reloaded.", NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
