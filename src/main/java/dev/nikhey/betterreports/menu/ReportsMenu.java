package dev.nikhey.betterreports.menu;

import dev.nikhey.betterreports.ReportService;
import dev.nikhey.betterreports.model.ChatLine;
import dev.nikhey.betterreports.model.Report;
import dev.nikhey.betterreports.model.ReportStatus;
import dev.nikhey.betterreports.storage.ReportStore;
import dev.nikhey.betterreports.util.TimeText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Staff GUI. The list view shows the work queue (open + claimed reports,
 * oldest first); clicking a report opens the detail view with the frozen chat
 * evidence and claim/teleport/resolve/dismiss buttons.
 */
public final class ReportsMenu implements Listener {

    private static final int REPORTS_PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;

    private static final int DETAIL_SIZE = 27;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_EVIDENCE = 11;
    private static final int SLOT_CLAIM = 13;
    private static final int SLOT_TP = 14;
    private static final int SLOT_RESOLVE = 15;
    private static final int SLOT_DISMISS = 16;
    private static final int SLOT_BACK = 22;

    private static final class ListHolder implements InventoryHolder {
        private final List<Report> reports;
        private final int page;
        private Inventory inventory;

        private ListHolder(List<Report> reports, int page) {
            this.reports = reports;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class DetailHolder implements InventoryHolder {
        private final Report report;
        private Inventory inventory;

        private DetailHolder(Report report) {
            this.report = report;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final ReportStore store;
    private final ReportService service;

    public ReportsMenu(Plugin plugin, ReportStore store, ReportService service) {
        this.plugin = plugin;
        this.store = store;
        this.service = service;
    }

    public void open(Player player) {
        openList(player, 1);
    }

    private void openList(Player player, int page) {
        // One page worth + 1 tells us whether a next page exists without counting.
        store.openReports(REPORTS_PER_PAGE + 1, (page - 1) * REPORTS_PER_PAGE).whenComplete((reports, error) -> {
            if (error != null || reports == null) {
                player.sendMessage(ReportService.prefixed("Failed to load the reports.", NamedTextColor.RED));
                return;
            }
            if (reports.isEmpty() && page == 1) {
                player.sendMessage(ReportService.prefixed("No open reports - all clear!", NamedTextColor.GREEN));
                return;
            }
            player.getScheduler().run(plugin, task -> {
                ListHolder holder = new ListHolder(reports, page);
                Inventory inv = Bukkit.createInventory(holder, 54,
                        Component.text("Open reports — page " + page, NamedTextColor.DARK_GRAY));
                holder.inventory = inv;
                int shown = Math.min(reports.size(), REPORTS_PER_PAGE);
                for (int i = 0; i < shown; i++) {
                    inv.setItem(i, listItem(reports.get(i)));
                }
                if (page > 1) {
                    inv.setItem(SLOT_PREV, navItem("« Previous page"));
                }
                if (reports.size() > REPORTS_PER_PAGE) {
                    inv.setItem(SLOT_NEXT, navItem("Next page »"));
                }
                player.openInventory(inv);
            }, null);
        });
    }

    public void openDetail(Player player, long id) {
        store.byId(id).thenCombine(store.evidence(id), (found, evidence) -> {
            if (found.isEmpty()) {
                player.sendMessage(ReportService.prefixed("Report #" + id + " does not exist.", NamedTextColor.RED));
                return null;
            }
            Report report = found.get();
            player.getScheduler().run(plugin, task -> {
                DetailHolder holder = new DetailHolder(report);
                Inventory inv = Bukkit.createInventory(holder, DETAIL_SIZE,
                        Component.text("Report #" + report.id() + " — " + report.targetName(),
                                NamedTextColor.DARK_GRAY));
                holder.inventory = inv;
                inv.setItem(SLOT_INFO, infoItem(report));
                inv.setItem(SLOT_EVIDENCE, evidenceItem(evidence));
                if (!report.status().isClosed()) {
                    inv.setItem(SLOT_CLAIM, button(Material.NAME_TAG,
                            report.status() == ReportStatus.CLAIMED ? "Unclaim" : "Claim",
                            report.status() == ReportStatus.CLAIMED
                                    ? "Claimed by " + report.claimerName()
                                    : "Assign this report to yourself"));
                    inv.setItem(SLOT_RESOLVE, button(Material.LIME_DYE, "Resolve",
                            "Close as handled (use /reports resolve " + report.id() + " <note> for a note)"));
                    inv.setItem(SLOT_DISMISS, button(Material.GRAY_DYE, "Dismiss",
                            "Close as invalid/unfounded"));
                }
                if (report.world() != null) {
                    inv.setItem(SLOT_TP, button(Material.ENDER_PEARL, "Teleport",
                            "To the reported location: " + report.world() + " "
                                    + report.x() + ", " + report.y() + ", " + report.z()));
                }
                inv.setItem(SLOT_BACK, navItem("« Back to the list"));
                player.openInventory(inv);
            }, null);
            return null;
        }).exceptionally(error -> {
            player.sendMessage(ReportService.prefixed("Failed to load report #" + id + ".", NamedTextColor.RED));
            return null;
        });
    }

    private ItemStack listItem(Report report) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(report.targetUuid()));
        }
        meta.displayName(Component.text("#" + report.id() + " " + report.targetName(), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Status: ", report.status().display(), report.status().color()));
        lore.add(line("Reason: ", report.reason()));
        lore.add(line("Reporter: ", report.reporterName()));
        lore.add(line("Filed: ", TimeText.ago(report.time())));
        if (report.bumps() > 0) {
            lore.add(line("Reported: ", (report.bumps() + 1) + "x"));
        }
        if (report.status() == ReportStatus.CLAIMED) {
            lore.add(line("Claimed by: ", report.claimerName()));
        }
        lore.add(Component.text("Click to open", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem(Report report) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(report.targetUuid()));
        }
        meta.displayName(Component.text(report.targetName(), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Status: ", report.status().display(), report.status().color()));
        lore.add(line("Reason: ", report.reason()));
        lore.add(line("Reporter: ", report.reporterName()));
        lore.add(line("Filed: ", TimeText.absolute(report.time()) + " (" + TimeText.ago(report.time()) + ")"));
        if (report.bumps() > 0) {
            lore.add(line("Reported: ", (report.bumps() + 1) + "x"));
        }
        if (report.gamemode() != null) {
            lore.add(line("Gamemode then: ", report.gamemode()));
            lore.add(line("Ping then: ", report.ping() + "ms"));
        }
        if (report.world() != null) {
            lore.add(line("Location: ", report.world() + " " + report.x() + ", " + report.y() + ", " + report.z()));
        }
        if (report.status() == ReportStatus.CLAIMED) {
            lore.add(line("Claimed by: ", report.claimerName() + " (" + TimeText.ago(report.claimTime()) + ")"));
        }
        if (report.status().isClosed()) {
            lore.add(line("Closed by: ", report.closerName() + " (" + TimeText.ago(report.closeTime()) + ")"));
            if (report.closeNote() != null && !report.closeNote().isBlank()) {
                lore.add(line("Note: ", report.closeNote()));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack evidenceItem(List<ChatLine> evidence) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Chat evidence", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (evidence.isEmpty()) {
            lore.add(Component.text("No chat captured for this report.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            for (ChatLine chatLine : evidence) {
                String message = chatLine.message();
                if (message.length() > 60) {
                    message = message.substring(0, 57) + "...";
                }
                lore.add(Component.text()
                        .append(Component.text(TimeText.clock(chatLine.time()) + " ",
                                NamedTextColor.DARK_GRAY))
                        .append(Component.text(chatLine.speakerName() + ": ", NamedTextColor.YELLOW))
                        .append(Component.text(message, NamedTextColor.WHITE))
                        .build()
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String label, String value) {
        return line(label, value, NamedTextColor.WHITE);
    }

    private static Component line(String label, String value, NamedTextColor valueColor) {
        return Component.text()
                .append(Component.text(label, NamedTextColor.GRAY))
                .append(Component.text(value == null ? "?" : value, valueColor))
                .build()
                .decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack navItem(String label) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material material, String label, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(description, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getInventory().getHolder();
        if (!(rawHolder instanceof ListHolder) && !(rawHolder instanceof DetailHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (rawHolder instanceof ListHolder holder) {
            handleListClick(player, holder, event.getSlot());
        } else {
            handleDetailClick(player, (DetailHolder) rawHolder, event.getSlot());
        }
    }

    private void handleListClick(Player player, ListHolder holder, int slot) {
        if (slot == SLOT_PREV && holder.page > 1) {
            openList(player, holder.page - 1);
            return;
        }
        if (slot == SLOT_NEXT && holder.reports.size() > REPORTS_PER_PAGE) {
            openList(player, holder.page + 1);
            return;
        }
        if (slot >= REPORTS_PER_PAGE || slot >= Math.min(holder.reports.size(), REPORTS_PER_PAGE)) {
            return;
        }
        openDetail(player, holder.reports.get(slot).id());
    }

    private void handleDetailClick(Player player, DetailHolder holder, int slot) {
        Report report = holder.report;
        switch (slot) {
            case SLOT_BACK -> openList(player, 1);
            case SLOT_CLAIM -> {
                if (report.status().isClosed()) {
                    return;
                }
                if (report.status() == ReportStatus.CLAIMED) {
                    service.unclaim(player, report.id());
                } else {
                    service.claim(player, report.id());
                }
                // The store runs on one worker thread, so this re-read is
                // queued after the claim update and never shows stale state.
                openDetail(player, report.id());
            }
            case SLOT_RESOLVE -> {
                if (report.status().isClosed()) {
                    return;
                }
                player.closeInventory();
                service.close(player, report.id(), ReportStatus.RESOLVED, null);
            }
            case SLOT_DISMISS -> {
                if (report.status().isClosed()) {
                    return;
                }
                player.closeInventory();
                service.close(player, report.id(), ReportStatus.DISMISSED, null);
            }
            case SLOT_TP -> {
                if (report.world() == null) {
                    return;
                }
                player.closeInventory();
                teleport(player, report);
            }
            default -> {
            }
        }
    }

    public void teleport(Player player, Report report) {
        player.getScheduler().run(plugin, task -> {
            World world = Bukkit.getWorld(report.world());
            if (world == null) {
                player.sendMessage(ReportService.prefixed("World '" + report.world()
                        + "' is not loaded.", NamedTextColor.RED));
                return;
            }
            player.teleportAsync(new Location(world, report.x() + 0.5, report.y(), report.z() + 0.5))
                    .thenAccept(ok -> player.sendMessage(ReportService.prefixed(
                            "Teleported to the reported location of #" + report.id() + ".",
                            NamedTextColor.GREEN)));
        }, null);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ListHolder || holder instanceof DetailHolder) {
            event.setCancelled(true);
        }
    }
}
