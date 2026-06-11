package dev.nikhey.betterreports.model;

import java.util.UUID;

/**
 * One report row. Staff fields ({@code claimer*}, {@code closer*}) are null
 * until the report reaches that stage. {@code bumps} counts duplicate reports
 * that were merged into this one.
 */
public record Report(long id,
                     long time,
                     UUID reporterUuid,
                     String reporterName,
                     UUID targetUuid,
                     String targetName,
                     String reason,
                     ReportStatus status,
                     UUID claimerUuid,
                     String claimerName,
                     long claimTime,
                     UUID closerUuid,
                     String closerName,
                     long closeTime,
                     String closeNote,
                     String world,
                     int x,
                     int y,
                     int z,
                     String gamemode,
                     int ping,
                     int bumps) {
}
