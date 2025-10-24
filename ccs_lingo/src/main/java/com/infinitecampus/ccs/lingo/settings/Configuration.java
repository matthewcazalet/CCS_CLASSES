package com.infinitecampus.ccs.lingo.settings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import com.infinitecampus.ccs.lingo.utility.LogHelper;

public class Configuration {
    private static final Logger logger = LogManager.getLogger(Configuration.class);
    private static final String SQL_GET_CONFIGURATION=
        "SELECT * FROM ccs_lng.CCS_LingoConfiguration WHERE active=1";
   
    private static volatile Configuration instance;

    private volatile String requestOutputDirectory = "";
    private volatile String documentFileDirectory ="";
    private volatile String campusApplicationName = "";
    private volatile String version = "";
    private volatile boolean debugMode = false;
    private volatile Connection backpackConnection = null;
    private volatile String backpackUrl = null;
    private volatile String backpackUsername = null;
    private volatile String backpackPassword = null;

    private Configuration() {
        // Private constructor to prevent instantiation
    }

    public static Configuration getInstance(){
        if (instance == null) {
           synchronized (Configuration.class) {
               if (instance == null) {
                   instance = new Configuration();
               }
           }
       }
       return instance;
   }
   /**
     * Gets the backpack connection. Creates it if it doesn't exist and credentials are valid.
     * Returns null if backpack is not configured or contains placeholder values.
     */
   public Connection getBackpackConnection() {

        if (!isBackpackConfigured()) {
            logger.info("Backpack connection not configured or contains placeholder values");
            return null;
        }
         // Check if existing connection is still valid
         if (backpackConnection != null) {
            try {
                if (!backpackConnection.isClosed() && backpackConnection.isValid(5)) {
                    return backpackConnection;
                }
            } catch (SQLException e) {
                logger.error("Existing backpack connection is invalid: {}", e.getMessage());
                backpackConnection = null;
            }
        }
         // Attempt to create new connection
         try {
            logger.info("Establishing Backpack connection to: {}", maskUrl(backpackUrl));
            backpackConnection = DriverManager.getConnection(backpackUrl, backpackUsername, backpackPassword);
            logger.info("Backpack connection established successfully");
            return backpackConnection;
        } catch (SQLException e) {
            logger.error("Error establishing Backpack connection: " + e.getMessage(), e);
            return null;
        }

       // if (backpackConnection == null) {
       //     try {
       //         backpackConnection = DriverManager.getConnection(backpackUrl, backpackUsername, backpackPassword);
      //      } catch (SQLException e) {
      //          logger.error("Error establishing Backpack connection: " + e.getMessage(), e);
      //      }
      //  }
      //  System.out.println("Backpack Connection: " + backpackConnection);
      //  return backpackConnection;
    }
        /**
     * Checks if backpack is properly configured with real values (not placeholders)
     */
    public boolean isBackpackConfigured() {
        if (backpackUrl == null || backpackUsername == null || backpackPassword == null) {
            return false;
        }
        
        // Check for common placeholder values
        String urlUpper = backpackUrl.toUpperCase();
        if (urlUpper.contains("SERVERNAME") || 
            urlUpper.contains("LOCALHOST") ||
            urlUpper.contains("PLACEHOLDER") ||
            urlUpper.contains("CHANGEME") ||
            urlUpper.contains("TODO") ||
            urlUpper.contains("TBD")) {
            return false;
        }
        
        // Check username/password for placeholders
        String userUpper = backpackUsername.toUpperCase();
        String passUpper = backpackPassword.toUpperCase();
        if (userUpper.contains("PLACEHOLDER") || userUpper.contains("CHANGEME") ||
            passUpper.contains("PLACEHOLDER") || passUpper.contains("CHANGEME")) {
            return false;
        }
        
        return true;
    }
    /**
     * Masks sensitive parts of URL for logging
     */
    private String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll("password=[^;]+", "password=***");
    }
    public String getRequestOutputDirectory(){
        return requestOutputDirectory;
    }
    public String getDocumentFileDirectory(){
        return documentFileDirectory;
    }
    public String getCampusApplicationName(){
        return campusApplicationName;
    }
    public void setCampusApplicationName(String name){
        this.campusApplicationName=name;
    }
    public boolean getDebugMode(){
        return debugMode;
    }
    public String getVersion(){
        return version;
    }

   
   public void loadConfiguration(Connection con,String appName) throws Exception {
    this.campusApplicationName=appName;
    try (PreparedStatement stmt = con.prepareStatement(SQL_GET_CONFIGURATION);
        ResultSet rs = stmt.executeQuery()) {
        // Check if the result set is empty
        if (!rs.isBeforeFirst()) { // No rows in result set
            throw new Exception("No active configuration found in the database.");
        }
        while (rs.next()) {
            String settingName = rs.getString("settingName").toLowerCase();
            String settingValue = rs.getString("settingValue");

            switch (settingName) {
                case "version":
                    version = settingValue;
                    break;
                case "requestoutputdirectory":
                    requestOutputDirectory = settingValue;
                    break;
                case "debugmode":
                    debugMode = Boolean.parseBoolean(settingValue);
                    break;
                case "documentfilepath":
                    documentFileDirectory = settingValue;
                    break;
                case "backpackurl":
                    backpackUrl = settingValue;
                    break;
                case "backpackusername":
                    backpackUsername = settingValue;
                    break;
                case "backpackpassword":
                    backpackPassword = settingValue;
                    break;                
                default:
                    logger.info("Unknown config key: " + settingName);                  
                    break;
            }
        }
        if (backpackUrl != null && backpackUsername != null && backpackPassword != null) {
           if(backpackConnection == null || backpackConnection.isClosed()){ 
                backpackConnection = getBackpackConnection();
           }
        }
        

} catch (SQLException e) {
   throw new Exception("Error loading configuration: " + e.getMessage(), e);
}
   }
   public void reconnectBackpack() throws SQLException {
    if (backpackUrl != null && backpackUsername != null && backpackPassword != null) {
        if (backpackConnection != null && !backpackConnection.isClosed()) {
            backpackConnection.close();
        }
        backpackConnection = DriverManager.getConnection(backpackUrl, backpackUsername, backpackPassword);
    } else {
        throw new IllegalStateException("Backpack DB credentials not loaded.");
    }
}

}
