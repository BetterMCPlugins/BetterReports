package dev.nikhey.betterreports.command;

import dev.nikhey.betterreports.ReportService;
import dev.nikhey.betterreports.config.Settings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class ReportCommand implements TabExecutor {

    private final Supplier<Settings> settings;
    private final ReportService service;

    public ReportCommand(Supplier<Settings> settings, ReportService service) {
        this.settings = settings;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage(ReportService.prefixed("Only players can file reports.", NamedTextColor.RED));
            return true;
        }
        Settings s = settings.get();
        if (args.length == 0 || (args.length < 2 && s.requireReason())) {
            reporter.sendMessage(ReportService.prefixed("Usage: /" + label + " <player> <reason>",
                    NamedTextColor.RED));
            return true;
        }
        String targetName = args[0];
        String reason = args.length > 1
                ? String.join(" ", List.of(args).subList(1, args.length))
                : "(no reason given)";

        OfflinePlayer target = Bukkit.getPlayerExact(targetName);
        if (target == null && s.allowOfflineTargets()) {
            // Cache-only lookup: never blocks on a Mojang API call.
            target = Bukkit.getOfflinePlayerIfCached(targetName);
        }
        if (target == null) {
            reporter.sendMessage(ReportService.prefixed(s.allowOfflineTargets()
                    ? "No player named '" + targetName + "' has ever played here."
                    : "'" + targetName + "' is not online.", NamedTextColor.RED));
            return true;
        }
        service.file(reporter, target, reason);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(sender) && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return settings.get().reasons().stream()
                    .filter(reason -> reason.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
