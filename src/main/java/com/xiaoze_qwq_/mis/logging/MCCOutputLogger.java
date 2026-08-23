package com.xiaoze_qwq_.mis.logging;

import com.xiaoze_qwq_.mis.config.MISConfig;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MCCOutputLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCCInServer");
    private final Path logDir;
    private final MISConfig.InstanceConfig config;  // 保存实例配置引用
    private static final long MAX_LOG_SIZE_BYTES = 1 * 1024 * 1024;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private PrintWriter currentWriter;
    private Path currentLogPath;
    private Instant currentStartTime;

    public MCCOutputLogger(Path logDirectory, MISConfig.InstanceConfig config) {
        this.logDir = logDirectory;
        this.config = config;
        initLogDir();
    }

    private void initLogDir() {
        try {
            if (!Files.exists(logDir)) Files.createDirectories(logDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create log directory: " + logDir, e);
        }
    }

    public synchronized void startNewLog() {
        if (currentWriter != null) currentWriter.close();
        currentStartTime = Instant.now();
        String fileName = "MCC-Output-" + DATE_FORMATTER.format(
                LocalDateTime.ofInstant(currentStartTime, ZoneId.systemDefault())) + ".log";
        currentLogPath = logDir.resolve(fileName);
        try {
            currentWriter = new PrintWriter(Files.newBufferedWriter(currentLogPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
            LOGGER.info("[{}] Started new MCC log: {}", config.id, currentLogPath);
        } catch (IOException e) {
            LOGGER.error("[{}] Failed to create log file", config.id, e);
            currentWriter = null;
        }
    }

    public synchronized void writeLine(String line) {
        if (currentWriter == null) return;
        String outputLine = line;
        if (config.saveLogTime) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(config.timeFormat));
            outputLine = "[" + timestamp + "] " + line;
        }
        currentWriter.println(outputLine);
        currentWriter.flush();
        checkSizeAndRotate();
    }

    private void checkSizeAndRotate() {
        if (currentLogPath != null && Files.exists(currentLogPath)) {
            try {
                if (Files.size(currentLogPath) >= MAX_LOG_SIZE_BYTES) {
                    LOGGER.info("[{}] Log file exceeded 1MB, rotating...", config.id);
                    rotate();
                }
            } catch (IOException e) {
                LOGGER.error("[{}] Failed to check log size", config.id, e);
            }
        }
    }

    public synchronized void rotate() {
        if (currentWriter == null) return;
        currentWriter.close();
        currentWriter = null;
        if (currentLogPath != null && Files.exists(currentLogPath)) {
            compressToTarGz(currentLogPath);
        }
        startNewLog();
    }

    private void compressToTarGz(Path logFile) {
        try {
            String baseName = logFile.getFileName().toString();
            String tarGzName = baseName.replace(".log", ".tar.gz");
            Path tarGzPath = logFile.resolveSibling(tarGzName);
            try (OutputStream fileOut = Files.newOutputStream(tarGzPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 BufferedOutputStream bufferedOut = new BufferedOutputStream(fileOut);
                 GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(bufferedOut);
                 TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {

                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                TarArchiveEntry entry = new TarArchiveEntry(logFile.toFile(), baseName);
                entry.setSize(Files.size(logFile));
                tarOut.putArchiveEntry(entry);
                Files.copy(logFile, tarOut);
                tarOut.closeArchiveEntry();
                tarOut.finish();
                LOGGER.info("[{}] Compressed log to {}", config.id, tarGzPath);
                Files.delete(logFile);
            }
        } catch (IOException e) {
            LOGGER.error("[{}] Failed to compress log file: " + logFile, config.id, e);
        }
    }

    public synchronized void closeAndFinalize() {
        if (currentWriter != null) {
            currentWriter.close();
            currentWriter = null;
        }
        if (currentLogPath != null && Files.exists(currentLogPath)) {
            compressToTarGz(currentLogPath);
        }
        LOGGER.info("[{}] MCC output logger closed and last log compressed", config.id);
    }
}