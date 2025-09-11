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
        "SELECT * FROM ccs_dev.CCS_LingoConfiguration WHERE active=1";
   
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
   public Connection getBackpackConnection() {

        if (backpackConnection == null) {
            try {
                backpackConnection = DriverManager.getConnection(backpackUrl, backpackUsername, backpackPassword);
            } catch (SQLException e) {
                logger.error("Error establishing Backpack connection: " + e.getMessage(), e);
            }
        }
        System.out.println("Backpack Connection: " + backpackConnection);
        return backpackConnection;
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
