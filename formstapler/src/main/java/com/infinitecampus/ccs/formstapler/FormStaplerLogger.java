package com.infinitecampus.ccs.formstapler;

import java.sql.*;

// A custom runtime exception for logging failures. 
class LoggerException extends RuntimeException {
    public LoggerException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class FormStaplerLogger {


    private FormStaplerLogger() {
        // This prevents anyone from creating an instance: new FormStaplerLogger()
    }

    private static final String SP_INSERT_LOG = "{call ccs_dev.CCS_Insert_FormStaplerLog(?,?,?,?,?)}";

    /**
     * Writes a hierarchical log entry to the database.
     *
     * @param conn The database connection. Must not be null.
     * @param parentID The ID of the parent log entry. Use 0 or less for a top-level entry.
     * @param userID The ID of the user associated with the log. Use 0 or less if not applicable.
     * @param message The log message. Must not be null.
     * @param isError True if this log entry represents an error condition.
     * @return The logID of the newly created database entry.
     * @throws LoggerException if the database operation fails.
     */
    public static int log(Connection conn, int parentID, int userID, String message, boolean isError) {
        // Add checks for required parameters
        if (conn == null) {
            throw new IllegalArgumentException("Database connection cannot be null.");
        }
        if (message == null) {
            throw new IllegalArgumentException("Log message cannot be null.");
        }

        // Use try-with-resources for automatic resource management
        try (CallableStatement stmt = conn.prepareCall(SP_INSERT_LOG)) {
            
            // Set IN parameters
            if (parentID > 0) {
                stmt.setInt(1, parentID);
            } else {
                stmt.setNull(1, Types.INTEGER);
            }

            if (userID > 0) {
                stmt.setInt(2, userID);
            } else {
                stmt.setNull(2, Types.INTEGER);
            }

            stmt.setString(3, message);
            stmt.setBoolean(4, isError);
            
            stmt.registerOutParameter(5, Types.INTEGER);

            stmt.execute();
            
            // Return the value from the output parameter
            return stmt.getInt(5);

        } catch (SQLException e) {
            // Don't just print the error. Wrap and re-throw it so the application
            // knows something went wrong. This is much more robust.
            throw new LoggerException("Failed to write to FormStaplerLog: " + e.getMessage(), e);
        }
    }
}