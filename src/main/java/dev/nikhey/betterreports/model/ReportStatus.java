package dev.nikhey.betterreports.model;

import net.kyori.adventure.text.format.NamedTextColor;

public enum ReportStatus {
    OPEN("Open", NamedTextColor.GOLD, 0xE67E22),
    CLAIMED("Claimed", NamedTextColor.AQUA, 0x3498DB),
    RESOLVED("Resolved", NamedTextColor.GREEN, 0x2ECC71),
    DISMISSED("Dismissed", NamedTextColor.GRAY, 0x95A5A6);

    private final String display;
    private final NamedTextColor color;
    private final int discordColor;

    ReportStatus(String display, NamedTextColor color, int discordColor) {
        this.display = display;
        this.color = color;
        this.discordColor = discordColor;
    }

    public String display() {
        return display;
    }

    public NamedTextColor color() {
        return color;
    }

    public int discordColor() {
        return discordColor;
    }

    public boolean isClosed() {
        return this == RESOLVED || this == DISMISSED;
    }
}
