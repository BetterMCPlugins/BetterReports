package dev.nikhey.betterreports.hook;

import org.bukkit.Server;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional bridge to BetterNotes' {@code NotesApi}, used to surface a player's
 * staff-note count in the report view and to offer an "add note" shortcut.
 *
 * Deliberately reflective: BetterReports has no compile-time dependency on
 * BetterNotes, so the two plugins build and release independently and neither
 * CI needs the other on its classpath. The API methods we call only traffic in
 * JDK types ({@link UUID}, {@link CompletableFuture}, {@link Integer}), so no
 * BetterNotes class ever has to be visible here.
 */
public final class NotesHook {

    private static final String API_CLASS = "dev.nikhey.betternotes.api.NotesApi";

    private final Object api;
    private final Method noteCount;
    private final Logger logger;

    private NotesHook(Object api, Method noteCount, Logger logger) {
        this.api = api;
        this.noteCount = noteCount;
        this.logger = logger;
    }

    /**
     * Resolves the hook if BetterNotes is enabled and exposes its service.
     * Returns null (rather than throwing) on any mismatch, so a future
     * BetterNotes that changed its API simply disables the integration.
     */
    public static NotesHook tryResolve(Server server, Logger logger) {
        if (!server.getPluginManager().isPluginEnabled("BetterNotes")) {
            return null;
        }
        try {
            for (Class<?> service : server.getServicesManager().getKnownServices()) {
                if (service.getName().equals(API_CLASS)) {
                    Object provider = server.getServicesManager().load(service);
                    if (provider == null) {
                        return null;
                    }
                    Method method = provider.getClass().getMethod("noteCount", UUID.class);
                    return new NotesHook(provider, method, logger);
                }
            }
            return null;
        } catch (Throwable t) {
            logger.warn("BetterNotes is present but its API could not be bound ({}). "
                    + "The notes integration stays off.", t.toString());
            return null;
        }
    }

    /** Active staff notes on a player; completes with -1 if the call fails. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Integer> noteCount(UUID target) {
        try {
            Object result = noteCount.invoke(api, target);
            return ((CompletableFuture<Integer>) result)
                    .exceptionally(error -> -1);
        } catch (Throwable t) {
            logger.warn("BetterNotes noteCount call failed: {}", t.toString());
            return CompletableFuture.completedFuture(-1);
        }
    }
}
