package com.infinitecampus.ccs.lingo.utility;

import com.infinitecampus.ccs.lingo.settings.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper class for consistent logging across the application.
 * Provides logging with class name and line number information.
 * Respects debug mode settings except for error logs which always get logged.
 */
public class LogHelper {
    private final Configuration config;
    private final Logger logger;
    private final String className;

    /**
     * Creates a LogHelper instance with default logger
     * @param config Configuration instance for controlling debug mode
     */
    public LogHelper(Configuration config) {
        this(config, LogManager.getLogger(LogHelper.class));
    }

    /**
     * Creates a LogHelper instance with specified logger
     * @param config Configuration instance for controlling debug mode
     * @param logger Specific logger instance to use
     * @throws IllegalArgumentException if config or logger is null
     */
    public LogHelper(Configuration config, Logger logger) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        if (logger == null) {
            throw new IllegalArgumentException("Logger cannot be null");
        }
        this.config = config;
        this.logger = logger;
        this.className = logger.getName().substring(logger.getName().lastIndexOf('.') + 1);
    }

    /**
     * Gets the caller's location information
     * @return String in format "ClassName:LineNumber"
     */
    private String getCallerLocation() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // Find the first element that's not part of the logging infrastructure
            for (int i = 1; i < stackTrace.length; i++) {
                String callerClassName = stackTrace[i].getClassName();
                if (!callerClassName.equals(this.getClass().getName()) && 
                    !callerClassName.contains("java.lang.Thread")) {
                    return String.format("%s:%d", 
                        callerClassName.substring(callerClassName.lastIndexOf('.') + 1),
                        stackTrace[i].getLineNumber());
                }
            }
        } catch (Exception e) {
            // Fallback to just class name if we can't get line number
            return className;
        }
        return className;
    }

    /**
     * Formats the message with caller location
     */
    private String formatMessage(String message) {
        return String.format("[%s] %s", getCallerLocation(), message != null ? message : "null");
    }

    /**
     * Logs a debug message if debug mode is enabled
     */
    public void logDebug(String message) {
        if (config.getDebugMode() ) {
            logger.info(formatMessage(message));
        }
    }

    /**
     * Logs a debug message with parameters if debug mode is enabled
     */
    public void logDebug(String message, Object... params) {
        if (config.getDebugMode() ) {
            logger.info(formatMessage(message), params);
        }
    }

    /**
     * Logs an info message regardless of debug mode
     */
    public void logInfo(String message) {
        if (logger.isInfoEnabled()) {  // Removed debug mode check
            logger.info(formatMessage(message));
        }
    }

    /**
     * Logs an info message with parameters regardless of debug mode
     */
    public void logInfo(String message, Object... params) {
        if (logger.isInfoEnabled()) {  // Removed debug mode check
            logger.info(formatMessage(message), params);
        }
    }

 
   /**
     * Logs a warning message regardless of debug mode
     */
    public void logWarn(String message) {
        if (logger.isWarnEnabled()) {  // Removed debug mode check
            logger.warn(formatMessage(message));
        }
    }

    /**
     * Logs a warning message with parameters regardless of debug mode
     */
    public void logWarn(String message, Object... params) {
        if (logger.isWarnEnabled()) {  // Removed debug mode check
            logger.warn(formatMessage(message), params);
        }
    }


    /**
     * Logs an error message - Always logs regardless of debug mode
     */
    public void logError(String message) {
        //if (logger.isErrorEnabled()) {
            logger.error(formatMessage(message));
        //}
    }

    /**
     * Logs an error message with exception - Always logs regardless of debug mode
     */
    public void logError(String message, Throwable throwable) {
       // if (logger.isErrorEnabled()) {
            logger.error(formatMessage(message), throwable);
        //}
    }

    /**
     * Logs an error message with parameters - Always logs regardless of debug mode
     */
    public void logError(String message, Object... params) {
        //if (logger.isErrorEnabled()) {
            logger.error(formatMessage(message), params);
        //}
    }

    /**
     * Logs an error message with parameters and exception - Always logs regardless of debug mode
     */
    public void logError(String message, Throwable throwable, Object... params) {
       // if (logger.isErrorEnabled()) {
            String formattedMessage = formatMessage(message);
            if (params != null && params.length > 0) {
                logger.error(formattedMessage, params, throwable);
            } else {
                logger.error(formattedMessage, throwable);
            }
        //}
    }

    /**
     * Creates a new LogHelper instance for a specific class
     */
    public LogHelper createLogger(Class<?> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class cannot be null");
        }
        return new LogHelper(config, LogManager.getLogger(clazz));
    }

    /**
     * Gets the underlying logger
     */
    public Logger getLogger() {
        return logger;
    }

    /**
     * Checks if debug logging is enabled
     */
    public boolean isDebugEnabled() {
        return config.getDebugMode() ;
    }

    /**
     * Gets the class name being used for logging
     */
    public String getClassName() {
        return className;
    }
}