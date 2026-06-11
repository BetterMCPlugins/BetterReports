package dev.nikhey.betterreports.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeText {

    private static final DateTimeFormatter ABSOLUTE =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private TimeText() {
    }

    public static String absolute(long epochMillis) {
        return ABSOLUTE.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String clock(long epochMillis) {
        return CLOCK.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String ago(long epochMillis) {
        return duration(System.currentTimeMillis() - epochMillis) + " ago";
    }

    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return (hours / 24) + "d " + (hours % 24) + "h";
    }
}
