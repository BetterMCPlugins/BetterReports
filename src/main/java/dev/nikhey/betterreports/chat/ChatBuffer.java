package dev.nikhey.betterreports.chat;

import dev.nikhey.betterreports.config.Settings;
import dev.nikhey.betterreports.model.ChatLine;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory ring buffer of recent chat per player. Lines only ever leave this
 * buffer when a report snapshots them as evidence - nothing is written to disk
 * for players who are never reported.
 */
public final class ChatBuffer implements Listener {

    private final Supplier<Settings> settings;
    private final Map<UUID, Deque<ChatLine>> buffers = new ConcurrentHashMap<>();

    public ChatBuffer(Supplier<Settings> settings) {
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Settings s = settings.get();
        if (!s.chatHistoryEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        ChatLine line = new ChatLine(System.currentTimeMillis(), player.getUniqueId(), player.getName(), message);
        Deque<ChatLine> buffer = buffers.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(line);
            int max = s.chatLines();
            while (buffer.size() > max) {
                buffer.removeFirst();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Bounded memory beats post-logout evidence: reports against offline
        // players simply carry no chat snapshot.
        buffers.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Merged snapshot of the target's and the reporter's recent chat, oldest
     * first, capped at the configured number of lines (newest are kept).
     */
    public List<ChatLine> snapshot(UUID target, UUID reporter) {
        List<ChatLine> merged = new ArrayList<>();
        copy(target, merged);
        if (!reporter.equals(target)) {
            copy(reporter, merged);
        }
        merged.sort(Comparator.comparingLong(ChatLine::time));
        int max = settings.get().snapshotLines();
        if (merged.size() > max) {
            return new ArrayList<>(merged.subList(merged.size() - max, merged.size()));
        }
        return merged;
    }

    private void copy(UUID player, List<ChatLine> into) {
        Deque<ChatLine> buffer = buffers.get(player);
        if (buffer == null) {
            return;
        }
        synchronized (buffer) {
            into.addAll(buffer);
        }
    }
}
