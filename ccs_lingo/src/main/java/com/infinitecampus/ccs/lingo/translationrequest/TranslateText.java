package com.infinitecampus.ccs.lingo.translationrequest;
/*
 * // Example usage
try (TranslateText translator = new TranslateText.Builder()
        .withConnection(connection)
        .withToken(token)
        .withConfiguration(config)
        .build()) {
    
    // Inside build():
    // 1. Creates new TranslateText instance
    // 2. Calls initialize()
    // 3. initialize() calls fetchConfigInfo()
    // 4. fetchConfigInfo() gets the provider type from database
    // 5. initializeTranslationServiceProvider() is called with the provider type
    // 6. The appropriate adapter is created based on the provider
    // 7. The adapter creates the actual translation service instance
    
    // Now we can use the translator
    translator.procedure();
    
} catch (Exception e) {
    logger.error("Translation failed", e);
}
 */
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.google.gson.Gson;
import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.translationprovider.azure.AzureTranslateText;
import com.infinitecampus.ccs.lingo.translationprovider.google.GoogleTranslateText;
import com.infinitecampus.ccs.lingo.translationprovider.aws.AwsTranslateText;
import com.infinitecampus.ccs.lingo.utility.LogHelper;


public class TranslateText implements AutoCloseable {
    private final LogHelper logger;

    // SQL Queries
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 serviceAccount, serviceprovider, REPLACE(tc.onCompleteSQL,'{selectedID}','?')[onCompleteSQL] " +
        "FROM [ccs_dev].[CCS_TranslationText] td " +
        "INNER JOIN ccs_dev.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 AND token = TRY_CAST(? AS UNIQUEIDENTIFIER)";

    private static final String SQL_GET_TRANSLATIONS = 
        "{call ccs_dev.CCS_Get_OLR_TranslationText(?)}";

    private static final String SQL_UPDATE_TRANSLATION = 
        "UPDATE [ccs_dev].[CCS_TranslationText] " +
        "SET [completed] = 1, [completedDate] = GETDATE(), outputData = ? " +
        "WHERE translationTextID = ?";
    private static String SQL_UPDATE_CAMPUS ="";

    // Instance fields
    private final Connection connection;
    private final String authToken;
    private final Configuration config;
    private TranslationServiceProvider translationServiceProvider;

    /**
     * Interface for translation services
     */
    public interface TranslationServiceProvider extends AutoCloseable {
        String translateText(String sourceText, String targetLanguage) throws Exception;
    }

    /**
     * Adapter classes for translation services
     */
    private class GoogleTranslationProvider implements TranslationServiceProvider {
        private final GoogleTranslateText translator;

        public GoogleTranslationProvider(Connection conn, String token, Configuration config) {
            logger.logDebug("GoogleTranslationProvider initialized with connection: {}, token: {}", conn, token);
            this.translator = new GoogleTranslateText.Builder()
                .withConnection(conn)
                .withToken(token)
                .withConfiguration(config)
                .build();
        }

        @Override
        public String translateText(String sourceText, String targetLanguage) throws Exception {
            return translator.translateText(sourceText, targetLanguage);
        }

        @Override
        public void close() throws Exception {
            if (translator instanceof AutoCloseable) {
                ((AutoCloseable) translator).close();
            }
        }
    }

    private class AzureTranslationProvider implements TranslationServiceProvider {
        private final AzureTranslateText translator;

        public AzureTranslationProvider(Connection conn, String token, Configuration config) {
            logger.logDebug("AzureTranslationProvider initialized with connection: {}, token: {}", conn, token);
            this.translator = new AzureTranslateText.Builder()
                .withConnection(conn)
                .withToken(token)
                .withConfiguration(config)
                .build();
        }

        @Override
        public String translateText(String sourceText, String targetLanguage) throws Exception {
            return translator.translateText(sourceText, targetLanguage);
        }

        @Override
        public void close() throws Exception {
            if (translator instanceof AutoCloseable) {
                ((AutoCloseable) translator).close();
            }
        }
    }

    private class AwsTranslationProvider implements TranslationServiceProvider {
        private final AwsTranslateText translator;

        public AwsTranslationProvider(Connection conn, String token, Configuration config) {
            logger.logDebug("AwsTranslationProvider initialized with connection: {}, token: {}", conn, token);
            this.translator = new AwsTranslateText.Builder()
                .withConnection(conn)
                .withToken(token)
                .withConfiguration(config)
                .build();
        }

        @Override
        public String translateText(String sourceText, String targetLanguage) throws Exception {
            return translator.translateText(sourceText, targetLanguage);
        }

        @Override
        public void close() throws Exception {
            if (translator instanceof AutoCloseable) {
                ((AutoCloseable) translator).close();
            }
        }
    }

    // Custom exceptions remain the same
    public static class TranslationException extends Exception {
        public TranslationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NoRecordsFoundException extends Exception {
        public NoRecordsFoundException(String message) {
            super(message);
        }
    }

    // Builder pattern implementation
    public static class Builder {
        private Connection connection;
        private String token;
        private Configuration config;

        public Builder withConnection(Connection connection) {           
            this.connection = connection;
            return this;
        }

        public Builder withToken(String token) {
            this.token = token;
            return this;
        }

        public Builder withConfiguration(Configuration config) {
            this.config = config;
            return this;
        }

        public TranslateText build() throws NoRecordsFoundException {
            return new TranslateText(connection, token, config);
        }
    }

    // Constructor
    private TranslateText(Connection connection, String token, Configuration configuration) 
            throws NoRecordsFoundException {
        // Initialize logger first
        this.logger = new LogHelper(configuration).createLogger(TranslateText.class);
        
        logger.logInfo("Initializing TranslateText with token: {}", token);

        // Authentication check with logging
        try {
            if (!Authenticate.isTranslatedTextAuthenticated(connection, token)) {
                logger.logError("Authentication failed for token: {}", token);
                throw new SecurityException("Invalid Token. Access Denied.");
            }
        } catch (Exception e) {
            logger.logError("Authentication verification failed", e);
            throw new SecurityException("Authentication process failed", e);
        }

        // Set instance fields
        this.connection = connection;
        this.authToken = token;
        this.config = configuration;

        // Initialize translation service
        try {
            initialize();
            logger.logInfo("TranslateText initialization completed successfully for token: {}", token);
        } catch (NoRecordsFoundException e) {
            logger.logError("No Records Found during initialization: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.logError("Initialization error for token {}: {}", token, e.getMessage());
            throw new RuntimeException("Failed to initialize translation service", e);
        }
    }

    // Initialization method 
    private void initialize() throws SQLException, NoRecordsFoundException {
        logger.logDebug("Fetching translation configuration for token: {}", authToken);
        
        try (PreparedStatement stmt = connection.prepareStatement(SQL_FETCH_CONFIG)) {
            stmt.setString(1, authToken);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    logger.logError("No configuration found for token: {}", authToken);
                    throw new NoRecordsFoundException("No records found for token: " + authToken);
                }
                
                String serviceProvider = rs.getString("serviceprovider");
                logger.logInfo("Found service provider: {} for token: {}", serviceProvider, authToken);
                                
                initializeTranslationServiceProvider(serviceProvider);

                SQL_UPDATE_CAMPUS = rs.getString("onCompleteSQL");
                logger.logDebug("SQL_UPDATE_CAMPUS_TEXT set to: {}", SQL_UPDATE_CAMPUS);
            }
        }
    }

    // Service provider initialization
    private void initializeTranslationServiceProvider(String provider) throws NoRecordsFoundException {
        logger.logDebug("Initializing translation service provider: {}", provider);
        if (provider == null || provider.isEmpty()) {
            logger.logError("Service provider is null or empty for token: {}", authToken);
            throw new NoRecordsFoundException("Service provider is null or empty for token: " + authToken);
        }
        try {
            switch (provider.toLowerCase()) {
                case "google":
                    translationServiceProvider = new GoogleTranslationProvider(connection, authToken, config);
                    break;
                case "azure":
                    translationServiceProvider = new AzureTranslationProvider(connection, authToken, config);
                    break;
                case "aws":
                    translationServiceProvider = new AwsTranslationProvider(connection, authToken, config);
                    break;
                default:
                    logger.logError("Unsupported service provider: {}", provider);
                    throw new NoRecordsFoundException("Unsupported service provider: " + provider);
            }
            
            logger.logInfo("Translation service provider initialized: {}", provider);
        } catch (Exception e) {
            logger.logError("Failed to initialize translation service provider: {}", provider, e);
            throw new NoRecordsFoundException("Failed to initialize service provider: " + provider);
        }
    }

    /**
     * Executes the translation procedure
     */
    public void procedure() throws TranslationException {
        logger.logInfo("Starting translation procedure for token: {}", authToken);
        
        try (CallableStatement stmt = connection.prepareCall(SQL_GET_TRANSLATIONS)) {
            stmt.setString(1, authToken);
            
            try (ResultSet rs = stmt.executeQuery()) {
                int processedCount = 0;
                int successCount = 0;
                int failureCount = 0;
                   // Check if there are any records
                   boolean hasRecords = rs.next();

                   if (!hasRecords) {
                       logger.logWarn("No records found for token: {}", authToken);
                       throw new NoRecordsFoundException("No records found for token: " + authToken);
                   }  
                do{
                    processedCount++;
                    boolean success = processTranslation(rs);
                    if (success) {
                        successCount++;
                    } else {
                        failureCount++;
                    }
                }while (rs.next());
                    
                
                
                logger.logInfo("Translation procedure completed. " +
                    "Total records: {}, Successful: {}, Failed: {} for token: {}", 
                    processedCount, successCount, failureCount, authToken);
            }
        } catch (Exception e) {
            logger.logError("Translation procedure failed for token: {}", authToken, e);
            throw new TranslationException("Translation procedure failed", e);
        }
    }

    // Process individual translation with success tracking
    private boolean processTranslation(ResultSet rs) throws SQLException {
        int translationId = rs.getInt("translationTextID");
        logger.logDebug("Processing translation for ID: {}", translationId);
        
        try {
            String sourceText = rs.getString("texttotranslate");
            String targetLanguage = rs.getString("language");
            
            logger.logDebug("Translating text (ID: {}) to language: {}", translationId, targetLanguage);
            
            String translatedText = translationServiceProvider.translateText(sourceText, targetLanguage);
            
            updateTranslationStatus(translationId, translatedText);
            updateCampusText(translationId);
            return true;
        } catch (Exception e) {
            logger.logError("Translation failed for ID {}: {}", translationId, e.getMessage());
            updateTranslationStatus(translationId, null);
            updateCampusText(translationId);
            return false;
        }
    }

    // Update translation status in the database
    private void updateTranslationStatus(int translationTextID, String translatedText) throws SQLException {
        logger.logDebug("Updating translation status for ID: {}", translationTextID);
        System.out.println("Updating translation status for ID: " + translationTextID);
        
        try (PreparedStatement stmt = connection.prepareStatement(SQL_UPDATE_TRANSLATION)) {
            if (translatedText == null || StringUtils.isBlank(translatedText)) {
                logger.logWarn("No translated text for ID: {}. Setting to null.", translationTextID);
                stmt.setNull(1, Types.NVARCHAR);
            } else {
                // Sanitize and escape the HTML content
              //  String sanitizedText = sanitizeAndEscapeHtml(translatedText.trim());
              translatedText = translatedText.replaceAll("'","''").trim();

                System.out.println("Setting translated text:"+translatedText); 
            
                logger.logDebug("Sanitized translated text: {}", translatedText);
                System.out.println("Sanitized translated text: "+translatedText);
                stmt.setString(1, translatedText);
            }
            stmt.setInt(2, translationTextID);

            int rowsUpdated = stmt.executeUpdate();
            System.out.println("Rows updated: " + rowsUpdated);
            if (rowsUpdated > 0) {
                logger.logDebug("Translation ID {} completed successfully", translationTextID);
            } else {
                logger.logWarn("Translation ID {} not found or not updated", translationTextID);
            }
        }
    }
//update campus translation text
private void updateCampusText(int translatedTextID) throws SQLException {
    if(SQL_UPDATE_CAMPUS == null || SQL_UPDATE_CAMPUS.isEmpty()) {
        logger.logDebug("SQL_UPDATE_CAMPUS is not set. Cannot update campus translation text.");
        return;
    }
    logger.logDebug("Updating campus translation text for ID: {}", translatedTextID);
    try (PreparedStatement stmt = connection.prepareStatement(SQL_UPDATE_CAMPUS)) {
        stmt.setInt(1, translatedTextID);
        stmt.executeUpdate();
        logger.logDebug("The OnComplete SQL for translation text ID {} completed successfully", translatedTextID);
    }

}


    // Closing method with comprehensive logging
    @Override
    public void close() throws Exception {
        logger.logInfo("Closing TranslateText for token: {}", authToken);
        
        if (translationServiceProvider != null) {
            try {
                translationServiceProvider.close();
                logger.logDebug("Translation service provider closed successfully");
            } catch (Exception e) {
                logger.logError("Error closing translation service provider", e);
                throw e;
            }
        }
    }
}