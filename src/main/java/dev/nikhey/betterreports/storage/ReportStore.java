package dev.nikhey.betterreports.storage;

import dev.nikhey.betterreports.model.ChatLine;
import dev.nikhey.betterreports.model.Report;
import dev.nikhey.betterreports.model.ReportStatus;
import org.slf4j.Logger;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite-backed store. All access is funneled through a single worker thread,
 * so the plugin never touches the database from a server thread.
 */
public final class ReportStore {

    /** Versioned schema contract for external read-only consumers (e.g. BetterPanel). */
    public static final int SCHEMA_VERSION = 1;

    public record GlobalStats(int total, int open, int claimed, int resolved, int dismissed,
                              long avgClaimMillis, long avgCloseMillis) {
    }

    public record StaffStats(int claimed, int resolved, int dismissed,
                             long avgClaimMillis, long avgCloseMillis) {
    }

    private final File file;
    private final Logger logger;
    private final ExecutorService io;
    private Connection conn;

    public ReportStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BetterReports-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void init() throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create data folder " + parent);
        }
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            // Versioned schema contract: external readers (BetterPanel) pin a
            // supported range against this. Seeded once, never auto-bumped.
            st.execute("CREATE TABLE IF NOT EXISTS schema_meta (version INTEGER NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT version FROM schema_meta LIMIT 1")) {
                if (!rs.next()) {
                    st.execute("INSERT INTO schema_meta (version) VALUES (" + SCHEMA_VERSION + ")");
                }
            }
            st.execute("""
                    CREATE TABLE IF NOT EXISTS reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        reporter_uuid TEXT NOT NULL,
                        reporter_name TEXT NOT NULL,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        status TEXT NOT NULL,
                        claimer_uuid TEXT,
                        claimer_name TEXT,
                        claim_time INTEGER NOT NULL DEFAULT 0,
                        closer_uuid TEXT,
                        closer_name TEXT,
                        close_time INTEGER NOT NULL DEFAULT 0,
                        close_note TEXT,
                        world TEXT,
                        x INTEGER NOT NULL DEFAULT 0,
                        y INTEGER NOT NULL DEFAULT 0,
                        z INTEGER NOT NULL DEFAULT 0,
                        gamemode TEXT,
                        ping INTEGER NOT NULL DEFAULT 0,
                        bumps INTEGER NOT NULL DEFAULT 0
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_reports_target ON reports(target_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_reports_reporter ON reports(reporter_uuid)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS evidence (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        report_id INTEGER NOT NULL,
                        time INTEGER NOT NULL,
                        speaker_uuid TEXT NOT NULL,
                        speaker_name TEXT NOT NULL,
                        message TEXT NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_evidence_report ON evidence(report_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS feedback_queue (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        message TEXT NOT NULL,
                        time INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_feedback_player ON feedback_queue(player_uuid)");
        }
    }

    /** Inserts a new report with its evidence snapshot and returns the new id. */
    public CompletableFuture<Long> create(Report draft, List<ChatLine> evidence) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                long id;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO reports (time, reporter_uuid, reporter_name, target_uuid, target_name,
                            reason, status, world, x, y, z, gamemode, ping, bumps)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0)""", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, draft.time());
                    ps.setString(2, draft.reporterUuid().toString());
                    ps.setString(3, draft.reporterName());
                    ps.setString(4, draft.targetUuid().toString());
                    ps.setString(5, draft.targetName());
                    ps.setString(6, draft.reason());
                    ps.setString(7, ReportStatus.OPEN.name());
                    ps.setString(8, draft.world());
                    ps.setInt(9, draft.x());
                    ps.setInt(10, draft.y());
                    ps.setInt(11, draft.z());
                    ps.setString(12, draft.gamemode());
                    ps.setInt(13, draft.ping());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("No generated key for report insert");
                        }
                        id = keys.getLong(1);
                    }
                }
                if (!evidence.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO evidence (report_id, time, speaker_uuid, speaker_name, message) VALUES (?,?,?,?,?)")) {
                        for (ChatLine line : evidence) {
                            ps.setLong(1, id);
                            ps.setLong(2, line.time());
                            ps.setString(3, line.speakerUuid().toString());
                            ps.setString(4, line.speakerName());
                            ps.setString(5, line.message());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                future.complete(id);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Optional<Report>> byId(long id) {
        return queryOne("SELECT * FROM reports WHERE id = ?", ps -> ps.setLong(1, id));
    }

    /** Open and claimed reports, oldest first - the staff work queue. */
    public CompletableFuture<List<Report>> openReports(int limit, int offset) {
        return query("SELECT * FROM reports WHERE status IN ('OPEN','CLAIMED') ORDER BY time ASC, id ASC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setInt(1, limit);
                    ps.setInt(2, offset);
                });
    }

    /** Closed reports, newest first. */
    public CompletableFuture<List<Report>> history(int limit, int offset) {
        return query("SELECT * FROM reports WHERE status IN ('RESOLVED','DISMISSED') ORDER BY close_time DESC, id DESC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setInt(1, limit);
                    ps.setInt(2, offset);
                });
    }

    public CompletableFuture<List<ChatLine>> evidence(long reportId) {
        CompletableFuture<List<ChatLine>> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM evidence WHERE report_id = ? ORDER BY time ASC, id ASC")) {
                ps.setLong(1, reportId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ChatLine> lines = new ArrayList<>();
                    while (rs.next()) {
                        lines.add(new ChatLine(
                                rs.getLong("time"),
                                UUID.fromString(rs.getString("speaker_uuid")),
                                rs.getString("speaker_name"),
                                rs.getString("message")));
                    }
                    future.complete(lines);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Newest unresolved report against this target filed after {@code since}, if any. */
    public CompletableFuture<Optional<Report>> findOpenByTarget(UUID target, long since) {
        return queryOne("SELECT * FROM reports WHERE target_uuid = ? AND status IN ('OPEN','CLAIMED') AND time >= ? ORDER BY time DESC, id DESC LIMIT 1",
                ps -> {
                    ps.setString(1, target.toString());
                    ps.setLong(2, since);
                });
    }

    public CompletableFuture<Integer> bump(long id) {
        return update("UPDATE reports SET bumps = bumps + 1 WHERE id = ?", ps -> ps.setLong(1, id));
    }

    public CompletableFuture<Integer> countOpenByReporter(UUID reporter) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM reports WHERE reporter_uuid = ? AND status IN ('OPEN','CLAIMED')")) {
                ps.setString(1, reporter.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    future.complete(rs.next() ? rs.getInt(1) : 0);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Returns true if the report was OPEN and is now claimed by the given staff member. */
    public CompletableFuture<Boolean> claim(long id, UUID staff, String staffName, long time) {
        return update("UPDATE reports SET status = 'CLAIMED', claimer_uuid = ?, claimer_name = ?, claim_time = ? WHERE id = ? AND status = 'OPEN'",
                ps -> {
                    ps.setString(1, staff.toString());
                    ps.setString(2, staffName);
                    ps.setLong(3, time);
                    ps.setLong(4, id);
                }).thenApply(rows -> rows > 0);
    }

    /** Returns true if the report was CLAIMED and is now open again. */
    public CompletableFuture<Boolean> unclaim(long id) {
        return update("UPDATE reports SET status = 'OPEN', claimer_uuid = NULL, claimer_name = NULL, claim_time = 0 WHERE id = ? AND status = 'CLAIMED'",
                ps -> ps.setLong(1, id)).thenApply(rows -> rows > 0);
    }

    /** Returns true if the report was still open/claimed and is now closed. */
    public CompletableFuture<Boolean> close(long id, ReportStatus status, UUID staff, String staffName,
                                            long time, String note) {
        if (!status.isClosed()) {
            throw new IllegalArgumentException("Not a closed status: " + status);
        }
        return update("UPDATE reports SET status = ?, closer_uuid = ?, closer_name = ?, close_time = ?, close_note = ? WHERE id = ? AND status IN ('OPEN','CLAIMED')",
                ps -> {
                    ps.setString(1, status.name());
                    ps.setString(2, staff.toString());
                    ps.setString(3, staffName);
                    ps.setLong(4, time);
                    ps.setString(5, note);
                    ps.setLong(6, id);
                }).thenApply(rows -> rows > 0);
    }

    public CompletableFuture<GlobalStats> stats() {
        CompletableFuture<GlobalStats> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                int total = 0;
                int open = 0;
                int claimed = 0;
                int resolved = 0;
                int dismissed = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status, COUNT(*) c FROM reports GROUP BY status");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int c = rs.getInt("c");
                        total += c;
                        switch (rs.getString("status")) {
                            case "OPEN" -> open = c;
                            case "CLAIMED" -> claimed = c;
                            case "RESOLVED" -> resolved = c;
                            case "DISMISSED" -> dismissed = c;
                            default -> { }
                        }
                    }
                }
                long avgClaim = 0;
                long avgClose = 0;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT (SELECT AVG(claim_time - time) FROM reports WHERE claim_time > 0) ac,
                               (SELECT AVG(close_time - time) FROM reports WHERE close_time > 0) ar""");
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        avgClaim = (long) rs.getDouble("ac");
                        avgClose = (long) rs.getDouble("ar");
                    }
                }
                future.complete(new GlobalStats(total, open, claimed, resolved, dismissed, avgClaim, avgClose));
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<StaffStats> staffStats(String staffName) {
        CompletableFuture<StaffStats> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                int claimed = 0;
                long avgClaim = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) c, AVG(claim_time - time) a FROM reports WHERE claimer_name = ? COLLATE NOCASE AND claim_time > 0")) {
                    ps.setString(1, staffName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            claimed = rs.getInt("c");
                            avgClaim = (long) rs.getDouble("a");
                        }
                    }
                }
                int resolved = 0;
                int dismissed = 0;
                long avgClose = 0;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT SUM(CASE WHEN status = 'RESOLVED' THEN 1 ELSE 0 END) r,
                               SUM(CASE WHEN status = 'DISMISSED' THEN 1 ELSE 0 END) d,
                               AVG(close_time - time) a
                        FROM reports WHERE closer_name = ? COLLATE NOCASE AND close_time > 0""")) {
                    ps.setString(1, staffName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            resolved = rs.getInt("r");
                            dismissed = rs.getInt("d");
                            avgClose = (long) rs.getDouble("a");
                        }
                    }
                }
                future.complete(new StaffStats(claimed, resolved, dismissed, avgClaim, avgClose));
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Counts of open + claimed reports, used for the PlaceholderAPI cache. */
    public CompletableFuture<int[]> openCounts() {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT SUM(CASE WHEN status = 'OPEN' THEN 1 ELSE 0 END), SUM(CASE WHEN status = 'CLAIMED' THEN 1 ELSE 0 END) FROM reports");
                 ResultSet rs = ps.executeQuery()) {
                future.complete(rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : new int[]{0, 0});
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void queueFeedback(UUID player, String message, long time) {
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO feedback_queue (player_uuid, message, time) VALUES (?,?,?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, message);
                ps.setLong(3, time);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to queue reporter feedback", e);
            }
        });
    }

    /** Returns and deletes all queued feedback messages for the player. */
    public CompletableFuture<List<String>> drainFeedback(UUID player) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                List<String> messages = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT message FROM feedback_queue WHERE player_uuid = ? ORDER BY time ASC, id ASC")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            messages.add(rs.getString("message"));
                        }
                    }
                }
                if (!messages.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM feedback_queue WHERE player_uuid = ?")) {
                        ps.setString(1, player.toString());
                        ps.executeUpdate();
                    }
                }
                future.complete(messages);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Deletes closed reports (and their evidence) older than the given number of days. */
    public CompletableFuture<Integer> purgeClosedOlderThan(int days) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        io.execute(() -> {
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM evidence WHERE report_id IN (SELECT id FROM reports WHERE status IN ('RESOLVED','DISMISSED') AND close_time < ?)")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM reports WHERE status IN ('RESOLVED','DISMISSED') AND close_time < ?")) {
                    ps.setLong(1, cutoff);
                    future.complete(ps.executeUpdate());
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private CompletableFuture<Integer> update(String sql, Binder binder) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                future.complete(ps.executeUpdate());
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private CompletableFuture<Optional<Report>> queryOne(String sql, Binder binder) {
        return query(sql, binder).thenApply(list -> list.isEmpty()
                ? Optional.empty()
                : Optional.of(list.getFirst()));
    }

    private CompletableFuture<List<Report>> query(String sql, Binder binder) {
        CompletableFuture<List<Report>> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Report> reports = new ArrayList<>();
                    while (rs.next()) {
                        reports.add(read(rs));
                    }
                    future.complete(reports);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static Report read(ResultSet rs) throws SQLException {
        ReportStatus status;
        try {
            status = ReportStatus.valueOf(rs.getString("status"));
        } catch (IllegalArgumentException e) {
            status = ReportStatus.OPEN;
        }
        return new Report(
                rs.getLong("id"),
                rs.getLong("time"),
                UUID.fromString(rs.getString("reporter_uuid")),
                rs.getString("reporter_name"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("reason"),
                status,
                optionalUuid(rs.getString("claimer_uuid")),
                rs.getString("claimer_name"),
                rs.getLong("claim_time"),
                optionalUuid(rs.getString("closer_uuid")),
                rs.getString("closer_name"),
                rs.getLong("close_time"),
                rs.getString("close_note"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("gamemode"),
                rs.getInt("ping"),
                rs.getInt("bumps"));
    }

    private static UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            logger.warn("Failed to close report database cleanly", e);
        }
    }
}
