package com.infinitecampus.ccs.formstapler;

// Add the necessary imports from the Log4j 2 library.
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A simple, static factory for obtaining pre-configured Log4j 2 loggers.
 * This class cannot be instantiated or subclassed.
 */
public final class AppLogger {

    /**
     * A private constructor to prevent this utility class from ever being instantiated.
     * All methods are static and should be called directly on the class itself.
     */
    private AppLogger() {
        // This space is intentionally left blank.
    }

    /**
     * Gets a logger for the specified class. 
     *
     * @param clazz The class for which to create the logger.
     * @return A Logger instance named after the class.
     */
    public static Logger getLogger(Class<?> clazz) {
        return LogManager.getLogger(clazz);
    }

    /**
     * Gets a logger with a specific, custom name.
     *
     * @param name The custom name of the logger.
     * @return A Logger instance.
     */
    public static Logger getLogger(String name) {
        return LogManager.getLogger(name);
    }
}