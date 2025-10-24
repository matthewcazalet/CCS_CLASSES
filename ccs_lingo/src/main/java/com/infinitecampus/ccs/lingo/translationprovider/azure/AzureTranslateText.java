package com.infinitecampus.ccs.lingo.translationprovider.azure;
/*usage exmaple:
 * try (AzureTranslateText translator = new AzureTranslateText.Builder()
        .withConnection(connection)
        .withToken(token)
        .withConfiguration(config)
        .build()) {
    
    String translatedText = translator.translateText("Hello, world!", "es");
    System.out.println("Translated text: " + translatedText);
    
} catch (Exception e) {
    logger.info("Translation failed", e);
}
 */
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.utility.LogHelper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Handles text translation using Azure Translator service.
 */
public class AzureTranslateText implements AutoCloseable {
    // Initialize logger as static final
    private static final LogHelper logger;
    
    // Static initialization block
    static {
        try {
            logger = new LogHelper(Configuration.getInstance()).createLogger(AzureTranslateText.class);
        } catch (Exception e) {
            // If logger initialization fails, throw runtime exception
            throw new ExceptionInInitializerError("Failed to initialize logger: " + e.getMessage());
        }
    }
   // Also add an instance logger for non-static methods
   private final LogHelper instanceLogger;

    // Constants
    private static final String DEFAULT_SOURCE_LANGUAGE = "en";
    private static final String DEFAULT_LOCATION = "eastus";
    private static final String DEFAULT_API_VERSION = "3.0";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 " +
        "JSON_VALUE(serviceAccount,'$.translator_key_id') translator_key_id, " +
        "JSON_VALUE(serviceAccount,'$.text_translation_endpoint') text_translation_endpoint, " +
        "COALESCE(JSON_VALUE(serviceAccount,'$.text_translation_apiversion'),?) apiversion, " +
        "COALESCE(JSON_VALUE(serviceAccount,'$.text_translation_location'),?) location, " +
        "serviceprovider, [serviceAccount] " +
        "FROM [ccs_lng].[CCS_TranslationText] td " +
        "INNER JOIN ccs_lng.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 AND serviceprovider='Azure' " +
        "AND token = TRY_CAST(? AS UNIQUEIDENTIFIER)";

    // Instance fields
    private final Connection connection;
    private final String authToken;
    //private final Configuration config;
    private final OkHttpClient httpClient;
    private AzureConfiguration azureConfig;

    /**
     * Azure configuration class
     */
    private static class AzureConfiguration {
        private final String keyId;
        private final String endpoint;
        private final String apiVersion;
        private final String location;

        public AzureConfiguration(String keyId, String endpoint, String apiVersion, String location) {
            this.keyId = keyId;
            this.endpoint = normalizeEndpoint(endpoint);
            this.apiVersion = apiVersion;
            this.location = location;
        }

        private String normalizeEndpoint(String endpoint) {
            return endpoint.endsWith("/") ? endpoint : endpoint + "/";
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

        public AzureTranslateText build() throws SecurityException {            
            logger.logInfo("Building AzureTranslateText with token: {}", token);
            return new AzureTranslateText(connection, token, config);
        }
    }

    private AzureTranslateText(Connection connection, String token, Configuration configuration) {
        this.instanceLogger = new LogHelper(configuration).createLogger(this.getClass());
        instanceLogger.logInfo("Initializing AzureTranslateText with token: {}", token);
        if (!Authenticate.isTranslatedTextAuthenticated(connection, token)) {
            instanceLogger.logError("Authentication failed for token: {}", token);
            throw new SecurityException("Invalid Token. Access Denied.");
        }
        this.connection = connection;
        this.authToken = token;
       // this.config = configuration;
        this.httpClient = new OkHttpClient();
        

        try {
            initialize();
            instanceLogger.logDebug("AzureTranslateText initialization completed successfully");
        } catch (Exception e) {
            instanceLogger.logError("Error during initialization: ", e);
            throw new RuntimeException("Failed to initialize Azure translation service", e);
        }
    }

    private void initialize() throws SQLException, NoRecordsFoundException {
        instanceLogger.logDebug("Initializing Azure translation service");
        fetchConfigInfo();
        instanceLogger.logDebug("Initialization completed successfully");
        
    }

    private void fetchConfigInfo() throws NoRecordsFoundException, SQLException {
        instanceLogger.logDebug("Fetching configuration information");
        try (PreparedStatement pstmt = connection.prepareStatement(SQL_FETCH_CONFIG)) {
            // Set both default values as parameters
            pstmt.setString(1, DEFAULT_API_VERSION);
            pstmt.setString(2, DEFAULT_LOCATION);
            pstmt.setString(3, authToken);

            instanceLogger.logDebug("Executing query with token: {}", authToken);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    instanceLogger.logWarn("No records found for token: {}", authToken);
                    throw new NoRecordsFoundException("No records found for token: " + authToken);
                }

                azureConfig = new AzureConfiguration(
                    rs.getString("translator_key_id"),
                    rs.getString("text_translation_endpoint"),
                    rs.getString("apiversion"),
                    rs.getString("location")
                );
                instanceLogger.logDebug("Configuration fetched successfully");            
            } catch (SQLException e) {
                instanceLogger.logError("Error fetching configuration", e);
                throw e;
            }      
        }
    }
  
    /**
     * Translates text to the target language.
     */
    public String translateText(String sourceText, String targetLanguage) throws TranslationException {
        instanceLogger.logInfo("Translating text to language: {}", targetLanguage);
        validateInput(sourceText, targetLanguage);

        try {
            JsonArray requestBody = createRequestBody(sourceText);
            Request request = createTranslationRequest(requestBody, targetLanguage);
            instanceLogger.logDebug("Sending translation request to Azure");
            try (Response response = httpClient.newCall(request).execute()) {
                String result = handleTranslationResponse(response);
                instanceLogger.logDebug("Translation completed successfully");
                return result;
            }
        } catch (Exception e) {
            instanceLogger.logError("Translation failed", e);
            throw new TranslationException("Translation failed", e);
        }
    }

    private JsonArray createRequestBody(String sourceText) {
        JsonArray jsonArray = new JsonArray();
        JsonObject textObject = new JsonObject();
        textObject.add("Text", new JsonPrimitive(sourceText));
        jsonArray.add(textObject);
        return jsonArray;
    }

    private Request createTranslationRequest(JsonArray requestBody, String targetLanguage) {
        String url = String.format("%stranslate?api-version=%s&from=%s&to=%s",
        azureConfig.endpoint, azureConfig.apiVersion, DEFAULT_SOURCE_LANGUAGE, targetLanguage);
    
        instanceLogger.logDebug("Request URL: {}", url);
        instanceLogger.logDebug("Request Body: {}", requestBody.toString());
        instanceLogger.logDebug("API Key (first 10 chars): {}", azureConfig.keyId.substring(0, Math.min(10, azureConfig.keyId.length())));
        instanceLogger.logDebug("Region: {}", azureConfig.location);
    
        return new Request.Builder()
            .url(url)
            .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
            .addHeader("Ocp-Apim-Subscription-Key", azureConfig.keyId)
            .addHeader("Ocp-Apim-Subscription-Region", azureConfig.location)
            .addHeader("Content-type", "application/json")
            .build();
    }

    private String handleTranslationResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            ResponseBody errorBody = response.body();
            String errorMessage = errorBody != null ? errorBody.string() : "No error body";
            instanceLogger.logError("Azure API Error - Code: {}, Body: {}", response.code(), errorMessage);
            throw new IOException("Unexpected response code: " + response + ", Error: " + errorMessage);
        }

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new IOException("Response body is null");
        }

        String responseBodyString = responseBody.string();
        JsonElement jsonElement = JsonParser.parseString(responseBodyString);
        JsonArray responseArray = jsonElement.getAsJsonArray();
        JsonObject firstObject = responseArray.get(0).getAsJsonObject();
        JsonArray translationsArray = firstObject.getAsJsonArray("translations");
        JsonObject translationObject = translationsArray.get(0).getAsJsonObject();
        
        return translationObject.get("text").getAsString();
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
       // httpClient.cl
       if (httpClient != null) {
        try{
            httpClient.connectionPool().evictAll();
        }catch (Exception e){
            instanceLogger.logError("Error closing httpClient", e);
        }}
        instanceLogger.logDebug("Closing Azure translation service resources");
        // Clean up resources if needed
    }
}
