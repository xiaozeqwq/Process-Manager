package com.xiaoze_qwq_.mis.util;

public class LogEntry {
    public final String message;
    public final long timestamp;

    public LogEntry(String message, long timestamp) {
        this.message = message;
        this.timestamp = timestamp;
    }
}