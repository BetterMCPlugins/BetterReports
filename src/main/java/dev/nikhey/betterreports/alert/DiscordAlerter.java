package dev.nikhey.betterreports.alert;

import dev.nikhey.betterreports.config.Settings;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public final class DiscordAlerter implements AlertSink {

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DiscordAlerter(Supplier<Settings> settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public void send(String title, String detail, int color) {
        Settings s = settings.get();
        if (!s.discordEnabled()) {
            return;
        }
        String json = """
                {"embeds":[{"title":%s,"description":%s,"color":%d,"timestamp":%s,"footer":{"text":"BetterReports"}}]}"""
                .formatted(
                        jsonString(title),
                        jsonString(detail),
                        color,
                        jsonString(Instant.now().toString()));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(s.webhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            logger.warn("Discord webhook delivery failed: {}", error.getMessage());
                        } else if (response.statusCode() >= 400) {
                            logger.warn("Discord webhook returned status {}", response.statusCode());
                        }
                    });
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid Discord webhook URL in config.yml: {}", e.getMessage());
        }
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
