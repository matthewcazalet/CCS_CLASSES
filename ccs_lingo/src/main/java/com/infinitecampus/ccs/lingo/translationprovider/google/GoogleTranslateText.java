package com.infinitecampus.ccs.lingo.translationprovider.google;
/* usage example:
 * try (GoogleTranslateText translator = new GoogleTranslateText.Builder()
        .withConnection(connection)
        .withToken(token)
        .withConfiguration(config)
        .build()) {
    
    String translatedText = translator.translateText("Hello, world!", "es");
    System.out.println("Translated text: " + translatedText);
    
} catch (Exception e) {
    logger.error("Translation failed", e);
}
 */
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
//import com.infinitecampus.ccs.lingo.translationprovider.aws.AwsTranslateText;
import com.google.cloud.translate.v3.*;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.api.gax.rpc.ApiException;

import com.infinitecampus.ccs.lingo.utility.LogHelper;

//import software.amazon.awssdk.services.translate.model.TranslateException;

/**
 * Handles text translation using Google Cloud Translation API.
 */
public class GoogleTranslateText implements AutoCloseable {
// Initialize logger as static final
    private static final LogHelper logger;
    
    // Static initialization block
    static {
        try {
            logger = new LogHelper(Configuration.getInstance()).createLogger(GoogleTranslateText.class);
        } catch (Exception e) {
            // If logger initialization fails, throw runtime exception
            throw new ExceptionInInitializerError("Failed to initialize logger: " + e.getMessage());
        }
    }
    private final LogHelper instanceLogger;

    // Constants
    private static final String DEFAULT_LOCATION = "global";
    private static final String DEFAULT_MIME_TYPE = "text/plain";
    
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 " +
        "JSON_VALUE(serviceAccount,'$.project_id') Google_projectID, " +
        "[serviceAccount] " +
        "FROM [ccs_lng].[CCS_TranslationText] td " +
        "INNER JOIN ccs_lng.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 " +
        "AND token = TRY_CAST(? AS UNIQUEIDENTIFIER)";

    // Instance fields
    private final Connection connection;
    private final String authToken;
    //private final Configuration config;
    private TranslationServiceClient translationClient;
    private GoogleConfiguration googleConfig;

    /**
     * Google configuration class
     */
    private static class GoogleConfiguration {
        private final LocationName parentLocation;
        private final String serviceAccount;

        public GoogleConfiguration(String projectId, String serviceAccount) {
            if (projectId == null || projectId.trim().isEmpty()) {
                throw new IllegalArgumentException("Project ID cannot be empty");
            }
            if (serviceAccount == null || serviceAccount.trim().isEmpty()) {
                throw new IllegalArgumentException("Service account cannot be empty");
            }

            this.serviceAccount = serviceAccount;
            // projectId is used here to create the LocationName
            this.parentLocation = LocationName.of(projectId, DEFAULT_LOCATION);
        }
    }

    /**
     * Custom exceptions
     */
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

    /**
     * Builder pattern implementation
     */
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

        public GoogleTranslateText build() throws SecurityException {
            logger.logInfo("Building GoogleTranslateText with token: {}", token);
            return new GoogleTranslateText(connection, token, config);
        }
    }

    private GoogleTranslateText(Connection connection, String token, Configuration configuration) {
        this.instanceLogger = new LogHelper(configuration).createLogger(this.getClass());
        instanceLogger.logInfo("Initializing GoogleTranslateText with token: {}", token);
        if (!Authenticate.isTranslatedTextAuthenticated(connection, token)) {
            instanceLogger.logError("Authentication failed for token: {}", token);
            throw new SecurityException("Invalid Token. Access Denied.");
        }

        this.connection = connection;
        this.authToken = token;
        //this.config = configuration;

        try {
            initialize();
            instanceLogger.logDebug("GoogleTranslateText initialization completed successfully");
        } catch (Exception e) {
            instanceLogger.logError("Error during initialization: ", e);
            throw new RuntimeException("Failed to initialize Google translation service", e);
        }
    }

    private void initialize() throws SQLException, NoRecordsFoundException, IOException {
        instanceLogger.logDebug("Initializing Google translation service");
        fetchConfigInfo();
        initializeTranslationServices();
        instanceLogger.logDebug("Initialization completed successfully");
    }

    private void fetchConfigInfo() throws NoRecordsFoundException, SQLException {
        instanceLogger.logDebug("Fetching configuration information");
        try (PreparedStatement pstmt = connection.prepareStatement(SQL_FETCH_CONFIG)) {
            pstmt.setString(1, authToken);
            instanceLogger.logDebug("Executing query with token: {}", authToken);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    instanceLogger.logWarn("No records found for token: {}", authToken);
                    throw new NoRecordsFoundException("No records found for token: " + authToken);
                }

                //String projectId = rs.getString("Google_projectID");
               // String serviceAccount = rs.getString("serviceAccount");

                
                instanceLogger.logDebug("Retrieved Google project ID: {}", rs.getString("Google_projectID"));


                googleConfig = new GoogleConfiguration(
                    rs.getString("Google_projectID"),  
                    rs.getString("serviceAccount")
                    );
                instanceLogger.logDebug("Configuration fetched successfully");              
            } catch (SQLException e) {
                instanceLogger.logError("Error fetching configuration", e);
                throw e;
            }      
        }
    }


    private void initializeTranslationServices() throws IOException {
        logger.logDebug("Initializing translation services");

        GoogleCredentials credentials;
        try (InputStream serviceAccountStream = 
            new ByteArrayInputStream(googleConfig.serviceAccount.getBytes(StandardCharsets.UTF_8))) {
            credentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
        }

        TranslationServiceSettings settings = TranslationServiceSettings.newBuilder()
            .setCredentialsProvider(() -> credentials)
            .build();

        translationClient = TranslationServiceClient.create(settings);
        logger.logDebug("Translation services initialized");
        
    }

    /**
     * Translates text to the target language.
     */
    public String translateText(String sourceText, String targetLanguage) throws TranslationException {
        instanceLogger.logInfo("Translating text to language: {}", targetLanguage);
        validateInput(sourceText, targetLanguage);
        
        try {
            TranslateTextRequest request = createTranslationRequest(sourceText, targetLanguage);
            System.out.println("Sending translation request to Google");
            TranslateTextResponse response = translationClient.translateText(request);
            System.out.println("Translation completed successfully");            
            return extractTranslation(response);
       } catch (ApiException e) { 
            logger.logError("Google translation failed: " + e.getMessage(), e);
            throw new TranslationException("Google translation failed", e);
        } catch (Exception e) {
            logger.logError("Unexpected error during translation: " + e.getMessage(), e);
            throw new TranslationException("Unexpected error during translation", e);
        }
    }

    private TranslateTextRequest createTranslationRequest(String sourceText, String targetLanguage) {
        return TranslateTextRequest.newBuilder()
            .setParent(googleConfig.parentLocation.toString())
            .setMimeType(DEFAULT_MIME_TYPE)
            .setTargetLanguageCode(targetLanguage)
            .addContents(sourceText)
            .build();
    }

    private String extractTranslation(TranslateTextResponse response) throws TranslationException {
        if (!response.getTranslationsList().isEmpty()) {
            return response.getTranslationsList().get(0).getTranslatedText();
        }
        throw new TranslationException("No translation was returned", null);
    }

    private void validateInput(String sourceText, String targetLanguage) {
        if (sourceText == null || sourceText.trim().isEmpty()) {
            throw new IllegalArgumentException("Source text cannot be empty");
        }
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            throw new IllegalArgumentException("Target language cannot be empty");
        }
    }


    @Override
    public void close() {
        instanceLogger.logDebug("Closing Google translation service resources");
        if (translationClient != null) {
            translationClient.close();
            instanceLogger.logDebug("Google Translate client closed successfully");
        }
    }
}