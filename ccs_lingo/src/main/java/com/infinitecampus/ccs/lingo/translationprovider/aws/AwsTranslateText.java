package com.infinitecampus.ccs.lingo.translationprovider.aws;
/*example usage:try (AwsTranslateText translator = new AwsTranslateText.Builder()
        .withConnection(connection)
        .withToken(token)
        .withConfiguration(config)
        .build()) {
    
    String translatedText = translator.translateText("Hello, world!", "es");
    System.out.println("Translated text: " + translatedText);
    
} catch (Exception e) {
    logger.error("Translation failed", e);
} */
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.utility.LogHelper;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.TranslateException;
import software.amazon.awssdk.services.translate.model.TranslateTextRequest;
import software.amazon.awssdk.services.translate.model.TranslateTextResponse;

/**
 * Handles text translation using AWS Translate service.
 */
public class AwsTranslateText implements AutoCloseable {
     // Initialize logger as static final
    private static final LogHelper logger;
    
    // Static initialization block
    static {
        try {
            logger = new LogHelper(Configuration.getInstance()).createLogger(AwsTranslateText.class);
        } catch (Exception e) {
            // If logger initialization fails, throw runtime exception
            throw new ExceptionInInitializerError("Failed to initialize logger: " + e.getMessage());
        }
    }
    // Also add an instance logger for non-static methods
    private final LogHelper instanceLogger;

    // Constants
    private static final Region AWS_REGION = Region.US_EAST_1;
    private static final String DEFAULT_SOURCE_LANGUAGE = "en";
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 JSON_VALUE(serviceAccount,'$.access_key') [access_key], " +
        "JSON_VALUE(serviceAccount,'$.secret_key') [secret_key], " +
        "serviceprovider, [serviceAccount] " +
        "FROM [ccs_dev].[CCS_TranslationText] td " +
        "INNER JOIN ccs_dev.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 AND serviceprovider='aws' " +
        "AND token = TRY_CAST(? AS UNIQUEIDENTIFIER);";

    // Instance fields
    private final Connection campusConnection;
    private final String authToken;
    //private final Configuration config;
    private TranslateClient awsTranslateClient;
    private AwsConfiguration awsConfig;

    /**
     * Custom exception for translation-related errors.
     */
    public static class TranslationException extends Exception {
        public TranslationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Custom exception for when no translation records are found.
     */
    public static class NoRecordsFoundException extends Exception {
        public NoRecordsFoundException(String message) {
            super(message);
        }
    }

    /**
     * Configuration class for AWS credentials and settings.
     */
    private static class AwsConfiguration {
        private final String accessKey;
        private final String secretKey;
        private final Region region;

        public AwsConfiguration(String accessKey, String secretKey, Region region) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.region = region;
        }
    }

    /**
     * Builder class for AwsTranslateText.
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

        public AwsTranslateText build() throws SecurityException {
            logger.logInfo("Building AwsTranslateText with token: {}", token);
            return new AwsTranslateText(connection, token, config);
        }
    }

    /**
     * Private constructor - use Builder to create instances.
     */
    private AwsTranslateText(Connection connection, String token, Configuration configuration) {
        this.instanceLogger = new LogHelper(configuration).createLogger(this.getClass());
        instanceLogger.logInfo("Initializing AWSTranslateText with token: {}", token);
        if (!Authenticate.isTranslatedTextAuthenticated(connection, token)) {
            throw new SecurityException("Invalid Token. Access Denied.");
        }

        this.campusConnection = connection;
        this.authToken = token;
        //this.config = configuration;
        //this.logger = new LogHelper(configuration).createLogger(this.getClass());

        try {
            initialize();
            instanceLogger.logDebug("AWSTranslateText initialization completed successfully");
        } catch (Exception e) {
             logger.logError("Error during initialization: ", e);
            throw new RuntimeException("Failed to initialize AWS translation service", e);
        }
    }

    /**
     * Initialize the AWS translation service.
     */
    private void initialize() throws SQLException, NoRecordsFoundException, IOException {
        instanceLogger.logDebug("Initializing AWS translation service");
        fetchConfigInfo();
        initializeTranslationServices();
        instanceLogger.logDebug("Initialization completed successfully");
    }

    /**
     * Fetch configuration information from the database.
     */    
     private void fetchConfigInfo() throws NoRecordsFoundException, SQLException {
        instanceLogger.logDebug("Fetching configuration information");
        try (PreparedStatement pstmt = campusConnection.prepareStatement(SQL_FETCH_CONFIG)) {
            pstmt.setString(1, authToken);
            instanceLogger.logDebug("Executing query with token: {}", authToken);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    instanceLogger.logWarn("No records found for token: {}", authToken);
                    throw new NoRecordsFoundException("No records found for token: " + authToken);
                }
                
                awsConfig = new AwsConfiguration(
                    rs.getString("access_key"),
                    rs.getString("secret_key"),
                    AWS_REGION
                );
                instanceLogger.logDebug("Configuration fetched successfully");
            } catch (SQLException e) {
                instanceLogger.logError("Error fetching configuration", e);
                throw e;
            }      
        }
    }

    /**
     * Initialize AWS translation services.
     */
    private void initializeTranslationServices() throws IOException {
        instanceLogger.logDebug("Initializing translation services");
        
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
            awsConfig.accessKey,
            awsConfig.secretKey
        );

        awsTranslateClient = TranslateClient.builder()
            .region(awsConfig.region)
            .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
            .build();

            instanceLogger.logDebug("Translation services initialized");
    }

    /**
     * Translate text to the target language.
     *
     * @param sourceText The text to translate
     * @param targetLanguage The target language code
     * @return The translated text
     * @throws TranslationException if translation fails
     */    
    public String translateText(String sourceText, String targetLanguage) throws TranslationException {
        instanceLogger.logInfo("Translating text to language: {}", targetLanguage);
        validateInput(sourceText, targetLanguage);
        
        try {
            TranslateTextRequest request = createTranslationRequest(sourceText, targetLanguage);
            instanceLogger.logDebug("Sending translation request to AWS");
            TranslateTextResponse response = awsTranslateClient.translateText(request);
            instanceLogger.logDebug("Translation completed successfully");
            return response.translatedText();
        } catch (TranslateException e) {
            instanceLogger.logError("AWS translation failed: " + e.awsErrorDetails().errorMessage(), e);
            throw new TranslationException("AWS translation failed", e);
        } catch (Exception e) {
            instanceLogger.logError("Unexpected error during translation: " + e.getMessage(), e);
            throw new TranslationException("Unexpected error during translation", e);
        }
    }

    /**
     * Create a translation request.
     */
    private TranslateTextRequest createTranslationRequest(String sourceText, String targetLanguage) {
        return TranslateTextRequest.builder()
            .sourceLanguageCode(DEFAULT_SOURCE_LANGUAGE)
            .targetLanguageCode(targetLanguage)
            .text(sourceText)
            .build();
    }

    /**
     * Validate input parameters.
     */
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
    instanceLogger.logDebug("Closing AWS translation service resources");
    if (awsTranslateClient != null) {
        try{
          // not available until later version  awsTranslateClient.close();
            com.infinitecampus.ccs.lingo.utility.AwsClientUtils.safeClose(awsTranslateClient, instanceLogger);
        } catch (Exception e) {
            instanceLogger.logError("Error closing AWS Translate client: ", e);
        }
        instanceLogger.logDebug("AWS Translate client closed successfully");
    }
}
}