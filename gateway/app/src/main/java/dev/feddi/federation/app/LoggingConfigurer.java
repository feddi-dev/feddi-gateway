package dev.feddi.federation.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Programmatically configures Logback file logging based on feddi-gateway.yml settings.
 * <p>
 * Creates a rolling file appender with:
 * - Daily rotation: one log file per day
 * - Size-based splitting: if a file exceeds 100MB, a new file is created (feddi-gateway-2026-03-22.0.log, .1.log, etc.)
 * - Retention: 30 days of history
 * - Total size cap: 1GB
 */
public class LoggingConfigurer {

    private static final String APPENDER_NAME = "FILE";
    private static final String LOG_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n";
    private static final String FILE_NAME = "feddi-gateway.log";
    private static final String ROLLING_PATTERN = "feddi-gateway-%d{yyyy-MM-dd}.%i.log";
    private static final String MAX_FILE_SIZE = "100MB";
    private static final int MAX_HISTORY_DAYS = 30;
    private static final String TOTAL_SIZE_CAP = "1GB";

    /**
     * Configure file logging for the given log directory.
     * Must be called early in startup (before heavy logging begins).
     */
    public static void configure(String logDir) {
        var logger = LoggerFactory.getLogger(LoggingConfigurer.class);

        // Ensure directory exists
        try {
            Files.createDirectories(Path.of(logDir));
        } catch (Exception e) {
            logger.error("Failed to create log directory '{}': {}", logDir, e.getMessage());
            return;
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Encoder
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(LOG_PATTERN);
        encoder.start();

        // Rolling file appender
        var appender = new RollingFileAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.setFile(Path.of(logDir, FILE_NAME).toString());

        // Rolling policy: daily + size-based
        var policy = new SizeAndTimeBasedRollingPolicy<ILoggingEvent>();
        policy.setContext(context);
        policy.setParent(appender);
        policy.setFileNamePattern(Path.of(logDir, ROLLING_PATTERN).toString());
        policy.setMaxFileSize(FileSize.valueOf(MAX_FILE_SIZE));
        policy.setMaxHistory(MAX_HISTORY_DAYS);
        policy.setTotalSizeCap(FileSize.valueOf(TOTAL_SIZE_CAP));
        policy.start();

        appender.setRollingPolicy(policy);
        appender.setEncoder(encoder);
        appender.start();

        // Add to root logger
        var rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(appender);

        logger.info("File logging configured: dir={}, maxFileSize={}, maxHistory={}d, totalCap={}",
                logDir, MAX_FILE_SIZE, MAX_HISTORY_DAYS, TOTAL_SIZE_CAP);
    }
}
