package dev.nikhey.betterreports.alert;

/** Receives report lifecycle alerts (new report, claim, close, ...). */
public interface AlertSink {

    void send(String title, String detail, int color);
}
