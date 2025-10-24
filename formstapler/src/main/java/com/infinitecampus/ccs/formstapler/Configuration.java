package com.infinitecampus.ccs.formstapler;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.Logger; 
import com.infinitecampus.prism.Prism;
import com.infinitecampus.system.CampusApp;

// A custom exception makes error handling much clearer.
class ConfigurationException extends Exception {
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
    public ConfigurationException(String message) {
        super(message);
    }
}

public class Configuration extends Prism {
    private static final Logger logger = AppLogger.getLogger(Configuration.class);

    public boolean isDebugMode() {
        return debugMode;
    }

    public String getVersion() {
        return version;
    }

    public String getDocumentFileDirectory() {
        return documentFileDirectory;
    }

    public String getSpecialEdFolderLocation() {
       return documentFileDirectory + File.separator + "specialed";
    }

    public String getDocumentBaseUrl(HttpServletRequest request) {
        return Prism.baseURL(request);
    }

    private static volatile Configuration instance = null;

    private static final String SQL_GET_CONFIGURATION ="SELECT settingName, settingValue FROM [ccs_dev].CCS_StapleConfiguration WHERE active=1";

    // Instance variables to hold the configuration data
    private String documentFileDirectory = "";
    private String version = "";
    private boolean debugMode = false;

    private Configuration() {super(); }

    public static Configuration getInstance(Connection con, String appName) throws ConfigurationException {
        // First check (no lock) improves performance.
        if (instance == null) {
            // Synchronize on the class to ensure only one thread can create the instance.
            synchronized (Configuration.class) {
                // Second check ensures it's only created once.
                if (instance == null) {
                    Configuration tempInstance = new Configuration();
                    //The configuration must be loaded after creation.
                    tempInstance.loadConfiguration(con, appName);
                    instance = tempInstance;
                }
            }
        }
        return instance;
    }

    private void loadConfiguration(Connection con, String appName) throws ConfigurationException {
        CampusApp app = Prism.getApp(appName);
        this.documentFileDirectory = app.documentFilePath + File.separator + "documentFileVault" + File.separator + app.appName;

        try (PreparedStatement stmt = con.prepareStatement(SQL_GET_CONFIGURATION);
             ResultSet rs = stmt.executeQuery()) {

            boolean foundConfig = false;
            while (rs.next()) {
                foundConfig = true; // Mark that we found at least one setting
                String settingName = rs.getString("settingName").toLowerCase();
                String settingValue = rs.getString("settingValue");

                switch (settingName) {
                    case "version":
                        this.version = settingValue;
                        break;
                    case "debugmode":
                        this.debugMode = Boolean.parseBoolean(settingValue);
                        break;
                    default:
                        // Ignore unknown settings
                        break;
                }
            }

            if (!foundConfig) {
                throw new ConfigurationException("No active configuration found in the database.");
            }

        } catch (SQLException e) {
            logger.fatal("CRITICAL DATABASE ERROR while loading configuration!", e);
            throw new ConfigurationException("Error loading configuration from database.", e);
        }
    }



}