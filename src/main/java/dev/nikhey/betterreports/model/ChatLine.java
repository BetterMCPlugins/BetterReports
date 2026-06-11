package dev.nikhey.betterreports.model;

import java.util.UUID;

/** One captured chat message, attached to a report as evidence. */
public record ChatLine(long time, UUID speakerUuid, String speakerName, String message) {
}
