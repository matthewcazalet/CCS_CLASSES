package com.infinitecampus.ccs.lingo.translationprovider.azure;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.TimeUnit;

import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.utility.LogHelper;
import com.infinitecampus.ccs.lingo.utility.FileUtilityHelper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.azure.storage.blob.*;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.core.util.BinaryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Handles document translation using Azure Translator and Blob Storage services.
 */
public class AzureTranslateDocument implements AutoCloseable {
    // Initialize logger as static final
    private static final LogHelper logger;
    
    // Static initialization block
    static {
        try {
            logger = new LogHelper(Configuration.getInstance()).createLogger(AzureTranslateDocument.class);
        } catch (Exception e) {
            // If logger initialization fails, throw runtime exception
            throw new ExceptionInInitializerError("Failed to initialize logger: " + e.getMessage());
        }
    }

    // SQL Queries
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 JSON_VALUE(serviceAccount,'$.translator_key_id') translator_key_id, " +
        "JSON_VALUE(serviceAccount,'$.document_translation_endpoint') document_translation_endpoint, " +
        "COALESCE(JSON_VALUE(serviceAccount,'$.text_translation_location'),'eastus') [location], " +
        "COALESCE(JSON_VALUE(serviceAccount,'$.document_translation_apiversion'),'2024-05-01') [apiversion], " +
        "JSON_VALUE(serviceAccount,'$.document_storage_name') storage_name, " +
        "JSON_VALUE(serviceAccount,'$.document_storage_key_id') storage_key_id, " +
        "JSON_VALUE(serviceAccount,'$.document_source_storage_name') source_name, " +
        "JSON_VALUE(serviceAccount,'$.document_target_storage_name') target_name, " +
        "JSON_VALUE(serviceAccount,'$.source_sasToken') source_sasToken, " +
        "JSON_VALUE(serviceAccount,'$.target_sasToken') target_sasToken, " +
        "serviceprovider, [serviceAccount] " +
        "FROM [ccs_dev].[CCS_TranslationDocument] td " +
        "INNER JOIN ccs_dev.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 AND serviceprovider='azure' " +
        "AND token = TRY_CAST(? AS UNIQUEIDENTIFIER);";

    // Instance fields
    private final Connection campusConnection;
    private final Connection backpackConnection;
    private final String authToken;
    private final Configuration config;
    private String outputFileLocation;
    
    // Azure services
    private OkHttpClient httpClient;
    private BlobServiceClient blobServiceClient;
    private BlobContainerClient sourceContainerClient;
    
    // Azure configuration
    private String translatorKeyId;
    private String translatorEndpoint;
    private String translatorRegion;
    private String apiVersion;
    private String storageAccountName;
    private String storageAccountKeyId;
    private String sourceContainerName;
    private String targetContainerName;
    private String sourceSasToken;
    private String targetSasToken;
    private String blobConnectionString;

    /**
     * Custom exception for when no translation records are found.
     */
    public static class NoRecordsFoundException extends Exception {
        public NoRecordsFoundException(String message) {
            super(message);
        }
    }

    /**
     * Builder class for AzureTranslateDocument.
     */
    public static class Builder {
        private Connection campusConnection;
        private Connection backpackConnection;
        private String token;
        private Configuration config;

        public Builder withCampusConnection(Connection connection) {
            this.campusConnection = connection;
            return this;
        }

        public Builder withBackpackConnection(Connection connection) {
            this.backpackConnection = connection;
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

        public AzureTranslateDocument build() throws SecurityException {
            logger.logInfo("Building AzureTranslateDocument with token: {}", token);
            return new AzureTranslateDocument(campusConnection, backpackConnection, token, config);
        }
    }

    /**
     * Private constructor - use Builder to create instances.
     */
    private AzureTranslateDocument(Connection campusConnection, Connection backpackConnection, String token, Configuration configuration) {
        if (!Authenticate.isTranslatedDocumentAuthenticated(campusConnection, token)) {
            logger.logError("Authentication failed for token: {}", token);
            throw new SecurityException("Invalid Token. Access Denied.");
        }

        this.campusConnection = campusConnection;
        this.backpackConnection = backpackConnection;
        this.authToken = token;
        this.config = configuration;

        try {
            initialize();
        } catch (Exception e) {
            logger.logError("Error during initialization: ", e);
            throw new RuntimeException("Failed to initialize Azure translation service", e);
        }
    }

    /**
     * Initialize the Azure translation service.
     */
    private void initialize() throws SQLException, NoRecordsFoundException, IOException {
        logger.logDebug("Initializing Azure document translation service");
        fetchConfigInfo();
        initializeTranslationServices();
        logger.logDebug("Initialization completed successfully");
    }

    /**
     * Fetch configuration information from the database.
     */
    private void fetchConfigInfo() throws NoRecordsFoundException, SQLException {
       
        try (PreparedStatement pstmt = campusConnection.prepareStatement(SQL_FETCH_CONFIG)) {
            pstmt.setString(1, authToken);
            logger.logDebug("Executing query with token: {}", authToken);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    logger.logWarn("No records found for token: {}", authToken);
                    throw new NoRecordsFoundException("No records found for token: " + authToken);
                }
                
                // Set Azure configuration
                translatorKeyId = rs.getString("translator_key_id");
                translatorEndpoint = rs.getString("document_translation_endpoint");
                translatorEndpoint = translatorEndpoint.endsWith("/") ? translatorEndpoint : translatorEndpoint + "/";
                translatorRegion = rs.getString("location");
                apiVersion = rs.getString("apiversion");
                
                // Set Azure Blob Storage configuration
                storageAccountName = rs.getString("storage_name");
                storageAccountKeyId = rs.getString("storage_key_id");
                sourceContainerName = rs.getString("source_name");
                targetContainerName = rs.getString("target_name");
                sourceSasToken = rs.getString("source_sasToken");
                targetSasToken = rs.getString("target_sasToken");
                
                // Setup output directory
                outputFileLocation = config.getRequestOutputDirectory() + File.separator + authToken;
                FileUtilityHelper.createFolderIfNotExists(outputFileLocation);
                
                logger.logDebug("Configuration fetched successfully");
            }
        }
    }

    /**
     * Initialize Azure translation services.
     */
    private void initializeTranslationServices() throws IOException {
        logger.logDebug("Initializing translation services");
        
        // Initialize HTTP client with longer timeouts
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build();
        
        // Fix the storage account key if it contains URL-safe Base64 characters
        String processedStorageKey = storageAccountKeyId;
        if (storageAccountKeyId != null && !storageAccountKeyId.isEmpty()) {
            // Check if the key contains URL-safe Base64 characters
            if (storageAccountKeyId.contains("-") || storageAccountKeyId.contains("_")) {
                logger.logDebug("Storage key contains URL-safe Base64 characters, converting...");
                // Convert URL-safe Base64 to standard Base64
                processedStorageKey = storageAccountKeyId
                    .replace('-', '+')
                    .replace('_', '/');
                
                // Add padding if necessary
                while (processedStorageKey.length() % 4 != 0) {
                    processedStorageKey += "=";
                }
                logger.logDebug("Storage key converted from URL-safe to standard Base64");
            }
        }
        
        // Initialize Azure Blob Storage client with the processed key
        blobConnectionString = String.format(
            "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
            storageAccountName, processedStorageKey);
            
        try {
            blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(blobConnectionString)
                .buildClient();
            logger.logDebug("Blob service client created successfully");
        } catch (Exception e) {
            logger.logError("Failed to create blob service client: {}", e.getMessage());
            // Log the sanitized key info for debugging (don't log the actual key!)
            logger.logError("Storage key contains hyphen: {}, underscore: {}", 
                storageAccountKeyId.contains("-"), storageAccountKeyId.contains("_"));
            throw new RuntimeException("Failed to initialize Azure Blob Storage client", e);
        }
        
        // Validate SAS tokens
        validateSasTokens();
            
        logger.logDebug("Translation services initialized");
    }
    private void initializeTranslationServices_DEP2() throws IOException {
        logger.logDebug("Initializing translation services");
        
        // Initialize HTTP client with longer timeouts
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build();
            
        // Initialize Azure Blob Storage client
        blobConnectionString = String.format(
            "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
            storageAccountName, storageAccountKeyId);
            
        blobServiceClient = new BlobServiceClientBuilder()
            .connectionString(blobConnectionString)
            .buildClient();
            
        // Validate SAS tokens
        validateSasTokens();
            
        logger.logDebug("Translation services initialized");
    }

    private void validateSasTokens() {
    try {
        // Decode and validate source SAS token
        sourceSasToken = URLDecoder.decode(sourceSasToken, StandardCharsets.UTF_8.toString());
        validateSasToken(sourceSasToken, "Source");

        // Decode and validate target SAS token
        targetSasToken = URLDecoder.decode(targetSasToken, StandardCharsets.UTF_8.toString());
        validateSasToken(targetSasToken, "Target");

        logger.logDebug("SAS tokens validated successfully");
    } catch (Exception e) {
        logger.logError("Error validating SAS tokens: {}", e.getMessage());
        throw new RuntimeException("Failed to validate SAS tokens", e);
    }
}
private void validateSasToken(String sasToken, String tokenType) {
    if (sasToken == null || sasToken.isEmpty()) {
        throw new IllegalArgumentException(tokenType + " SAS token is null or empty");
    }

    // Check required components
    String[] requiredParams = {"sp", "st", "se", "sv", "sr", "sig"};
    for (String param : requiredParams) {
        if (!sasToken.contains(param + "=")) {
            throw new IllegalArgumentException(tokenType + 
                " SAS token missing required parameter: " + param);
        }
    }

    logger.logDebug("{} SAS token validation passed", tokenType);
}
private String constructBlobUrl(String containerName, String fileName) {
    try {
        String url = String.format("https://%s.blob.core.windows.net/%s/%s",
            storageAccountName,
            containerName,
            fileName);
        logger.logDebug("Constructed base blob URL: {}", url);
        return url;
    } catch (Exception e) {
        logger.logError("Error constructing blob URL: {}", e.getMessage());
        throw new RuntimeException("Failed to construct blob URL", e);
    }
}
    private void initializeTranslationServices_DEP() throws IOException {
        logger.logDebug("Initializing translation services");
        
        // Initialize HTTP client with longer timeouts
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)  // 3 minutes
            .writeTimeout(180, TimeUnit.SECONDS)
            .build();
            
        // Initialize Azure Blob Storage client
        blobConnectionString = String.format(
            "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
            storageAccountName, storageAccountKeyId);
            
        blobServiceClient = new BlobServiceClientBuilder()
            .connectionString(blobConnectionString)
            .buildClient();
            
        logger.logDebug("Translation services initialized");
    }

    /**
     * Translate a document from Campus.
     */
    public void translateCampusDocument(String inputFilePath, 
                                    String outputFileName, String targetLanguage) throws IOException, SQLException {
    System.out.println("!Processing Campus document translation for ID: " + inputFilePath);
    
    try {
        // Debug: Test container client
        BlobContainerClient testClient = blobServiceClient.getBlobContainerClient(sourceContainerName);
        System.out.println("Successfully created test container client");
        
        // Debug: Get file name
        String fileName = FileUtilityHelper.getFileName(inputFilePath);
        System.out.println("File name extracted: " + fileName);
        
        // Debug: Read PDF
        byte[] pdfData = null;
        try {
            pdfData = getPdfBytes(inputFilePath);
            System.out.println("PDF read successfully, size: " + pdfData.length + " bytes");
        } catch (Exception e) {
            System.out.println("Error reading PDF: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
        
        // Debug: Translate PDF
        byte[] translatedData = null;
        try {
            System.out.println("Starting translatePdf method...");
            translatedData = translatePdf(pdfData, fileName, targetLanguage);
            System.out.println("Translation completed successfully");
        } catch (Exception e) {
            System.out.println("Error in translatePdf: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }

        String outputFilePath = outputFileLocation + File.separator + outputFileName;
        logger.logDebug("Output file path: {}", outputFilePath);
        
        // Ensure the output file does not already exist
        FileUtilityHelper.deleteFileIfExists(outputFilePath);
        
        // Write the translated content to the output location
        FileUtilityHelper.WriteFilesOut(outputFilePath, translatedData);
        System.out.println("!COMPLETED");
    } catch (IOException e) {
        System.out.println("Error during document translation for azure: " + e);
        e.printStackTrace();
        throw new IOException("Error during translation process", e);
    } catch(Exception ex) {
        System.out.println("Error during document translation for azure: " + ex);
        ex.printStackTrace();
        throw new IOException("Error during translation process", ex);
    }
}
    public void translateCampusDocument_DEP(String inputFilePath, 
                                        String outputFileName, String targetLanguage) throws IOException, SQLException {
        //logger.logInfo("Processing Campus document translation for ID: {}", translationDocumentID);
        System.out.println("!Processing Campus document translation for ID: " + inputFilePath);
        try {
            String fileName = FileUtilityHelper.getFileName(inputFilePath);
            byte[] pdfData = getPdfBytes(inputFilePath);
            byte[] translatedData = translatePdf(pdfData, fileName, targetLanguage);

            String outputFilePath = outputFileLocation + File.separator + outputFileName;
            logger.logDebug("Output file path: {}", outputFilePath);
            
            // Ensure the output file does not already exist
            FileUtilityHelper.deleteFileIfExists(outputFilePath);
            
            // Write the translated content to the output location
            FileUtilityHelper.WriteFilesOut(outputFilePath, translatedData);
            System.out.println("!COMPLETED");
         //   markRequestCompleted(translationDocumentID, FileUtilityHelper.getFileName(outputFilePath));
       //     logger.logInfo("Successfully completed translation for ID: {}", translationDocumentID);
        } catch (IOException e) {
              System.out.println("Error during document translation for azure {}"+ e);
            throw new IOException("Error during translation process", e);
        }
        catch(Exception ex){
            System.out.println("Error during document translation for azure {}"+ ex);
            throw new IOException("Error during translation process", ex);
        }
    }

    /**
     * Translate a document from Backpack.
     */
    public void translateBackpackDocument(int translationDocumentID, int documentID, 
                                         String inputFilePath, String targetLanguage, 
                                         String fullLanguage) throws IOException, SQLException {
        logger.logInfo("Processing Backpack document translation for ID: {}", translationDocumentID);
        
        try {
            String fileName = FileUtilityHelper.getFileName(inputFilePath);
            byte[] pdfData = getPdfBytes(inputFilePath);
            byte[] translatedData = translatePdf(pdfData, fileName, targetLanguage);
            
            // Process database operations
            processBackpackDatabase(translationDocumentID, documentID, translatedData, targetLanguage, fullLanguage);
            
            logger.logInfo("Successfully completed translation for ID: {}", translationDocumentID);
        } catch (IOException e) {
            logger.logError("Error during document translation for ID: {}", translationDocumentID, e);
            throw new IOException("Error during translation process", e);
        }
    }

    /**
     * Get PDF file as byte array.
     */
    private byte[] getPdfBytes(String filePath) throws IOException {
        try (InputStream stream = new FileInputStream(filePath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Translate PDF document using Azure services.
     */
    private byte[] translatePdf(byte[] pdfData, String inputFileName, String targetLanguage) throws IOException {
        String uploadedFileName = authToken + "_" + inputFileName;
        System.out.println("Uploaded file name will be: " + uploadedFileName);
        
        try {
            // Debug: Delete existing document
            try {
                deleteDocument(sourceContainerName, uploadedFileName);
                System.out.println("Deleted existing source document (if any)");
            } catch (Exception e) {
                System.out.println("Error deleting source document: " + e.getMessage());
            }
            
            // Debug: Upload document
            try {
                System.out.println("Uploading document to blob storage...");
                sourceContainerClient = blobServiceClient.getBlobContainerClient(sourceContainerName);
                BlobClient blobClient = sourceContainerClient.getBlobClient(uploadedFileName);
                blobClient.upload(BinaryData.fromBytes(pdfData), true);
                System.out.println("File uploaded successfully");
            } catch (Exception e) {
                System.out.println("Error uploading file: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
            
            // Debug: Delete target document
            try {
                deleteDocument(targetContainerName, uploadedFileName);
                System.out.println("Deleted existing target document (if any)");
            } catch (Exception e) {
                System.out.println("Error deleting target document: " + e.getMessage());
            }
            
            // Debug: Request translation
            String translationJobId = null;
            try {
                System.out.println("Requesting translation...");
                translationJobId = requestSingleDocumentTranslation(uploadedFileName, targetLanguage);
                System.out.println("Translation job ID: " + translationJobId);
            } catch (Exception e) {
                System.out.println("Error requesting translation: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
            
            // Debug: Check job status
            try {
                System.out.println("Checking job status...");
                checkJobStatus(translationJobId);
                System.out.println("Job completed successfully");
            } catch (Exception e) {
                System.out.println("Error checking job status: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
            
            // Download translated document
            BlobClient blobClient = new BlobClientBuilder()
                .connectionString(blobConnectionString)
                .containerName(targetContainerName)
                .blobName(uploadedFileName)
                .buildClient();
                
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            
            return outputStream.toByteArray();
        } catch (InterruptedException e) {
            logger.logError("Translation process interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new IOException("Translation process interrupted", e);
        } finally {
            // Clean up
            try {
                deleteDocument(targetContainerName, uploadedFileName);
                deleteDocument(sourceContainerName, uploadedFileName);
            } catch (Exception e) {
                System.out.println("Error during cleanup: " + e.getMessage());
            }
        }
    }
    private byte[] translatePdf_DEP(byte[] pdfData, String inputFileName, String targetLanguage) throws IOException {
        String uploadedFileName = authToken + "_" + inputFileName;
        
        try {
            // Delete document if it exists
            deleteDocument(sourceContainerName, uploadedFileName);
            
            // Upload document to Azure Blob Storage
            sourceContainerClient = blobServiceClient.getBlobContainerClient(sourceContainerName);
            BlobClient blobClient = sourceContainerClient.getBlobClient(uploadedFileName);
            blobClient.upload(BinaryData.fromBytes(pdfData), true);
            
            logger.logDebug("File uploaded successfully: {} as {}", inputFileName, uploadedFileName);
            
            // Delete target document if it exists
            deleteDocument(targetContainerName, uploadedFileName);
            
            // Request translation
            String translationJobId = requestSingleDocumentTranslation(uploadedFileName, targetLanguage);
            
            // Wait for translation to complete
            checkJobStatus(translationJobId);
            
            // Download translated document
            blobClient = new BlobClientBuilder()
                .connectionString(blobConnectionString)
                .containerName(targetContainerName)
                .blobName(uploadedFileName)
                .buildClient();
                
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            
            return outputStream.toByteArray();
        } catch (BlobStorageException e) {
            logger.logError("Failed to upload file: {}", e.getMessage());
            throw new IOException("Failed to upload file to Azure Blob Storage", e);
        } catch (InterruptedException e) {
            logger.logError("Translation process interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            throw new IOException("Translation process interrupted", e);
        } finally {
            // Clean up by deleting documents
            try {
                deleteDocument(targetContainerName, uploadedFileName);
            } finally {
                try {
                    deleteDocument(sourceContainerName, uploadedFileName);
                } finally {
                    logger.logDebug("Deleted source and target documents: {} and {}", 
                        sourceContainerName, targetContainerName);
                }
            }
        }
    }

    /**
     * Request translation for a single document.
     */
    private String requestSingleDocumentTranslation(String documentName, String targetLanguage) throws IOException {
        // Construct base URLs
        String sourceBaseUrl = constructBlobUrl(sourceContainerName, documentName);
        String targetBaseUrl = constructBlobUrl(targetContainerName, documentName);
        
        // Add SAS tokens
        String sourceUrl = sourceBaseUrl + "?" + sourceSasToken;
        String targetUrl = targetBaseUrl + "?" + targetSasToken;
        
        logger.logDebug("Source URL constructed: {}", sourceUrl);
        logger.logDebug("Target URL constructed: {}", targetUrl);
    
        // Create JSON request body
        JsonObject json = new JsonObject();
        JsonArray inputs = new JsonArray();
        JsonObject input = new JsonObject();
        
        // Add storageType
        input.addProperty("storageType", "File");
        
        // Define the source
        JsonObject source = new JsonObject();
        source.addProperty("sourceUrl", sourceUrl);
        
        // Define the targets
        JsonArray targets = new JsonArray();
        JsonObject target = new JsonObject();
        target.addProperty("targetUrl", targetUrl);
        target.addProperty("language", targetLanguage);
        targets.add(target);
        
        // Add source and targets to the input
        input.add("source", source);
        input.add("targets", targets);
        
        // Add input to the inputs array
        inputs.add(input);
        
        // Add the inputs array to the JSON object
        json.add("inputs", inputs);

         // Prepare the request body
         RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
         String translationUrl = translatorEndpoint + "translator/document/batches?api-version=" + apiVersion;
         
         logger.logDebug("Translation URL: {}", translationUrl);
         logger.logDebug("Request body: {}", json.toString());
         
         // Create the request
         Request request = new Request.Builder()
             .url(translationUrl)
             .post(body)
             .addHeader("Ocp-Apim-Subscription-Key", translatorKeyId)
             .addHeader("Ocp-Apim-Subscription-Region", translatorRegion)
             .addHeader("Content-Type", "application/json")
             .build();
         
         logger.logDebug("Sending translation request to Azure");
         
         try (Response response = httpClient.newCall(request).execute()) {
             String responseBody = response.body().string();
             
             if (!response.isSuccessful()) {
                 logger.logError("Translation request failed: {}", responseBody);
                 throw new IOException("Translation request failed: " + responseBody);
             }
             
             JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();
             String jobId = responseJson.get("id").getAsString();
             logger.logDebug("Translation job created with ID: {}", jobId);
             
             return jobId;
         }
    }
    private String requestSingleDocumentTranslation_DEP(String documentName, String targetLanguage) throws IOException {
        // Create source and target URLs with SAS tokens
        String sourceUrl = String.format("https://%s.blob.core.windows.net/%s/%s?%s",
            storageAccountName, sourceContainerName, documentName, sourceSasToken);
        String targetUrl = String.format("https://%s.blob.core.windows.net/%s/%s?%s",
            storageAccountName, targetContainerName, documentName, targetSasToken);
        
        logger.logDebug("Source URL: {}", sourceUrl);
        logger.logDebug("Target URL: {}", targetUrl);

        // Create JSON request body
        JsonObject json = new JsonObject();
        JsonArray inputs = new JsonArray();
        JsonObject input = new JsonObject();
        
        // Add storageType
        input.addProperty("storageType", "File");
        
        // Define the source with the sourceUrl
        JsonObject source = new JsonObject();
        source.addProperty("sourceUrl", sourceUrl);
        
        // Define the targets with the language
        JsonArray targets = new JsonArray();
        JsonObject target = new JsonObject();
        target.addProperty("targetUrl", targetUrl);
        target.addProperty("language", targetLanguage);
        targets.add(target);
        
        // Add source and targets to the input
        input.add("source", source);
        input.add("targets", targets);
        
        // Add input to the inputs array
        inputs.add(input);
        
        // Add the inputs array to the JSON object
        json.add("inputs", inputs);
        
        // Prepare the request body
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        String translationUrl = translatorEndpoint + "translator/document/batches?api-version=" + apiVersion;
        
        logger.logDebug("Translation URL: {}", translationUrl);
        logger.logDebug("Request body: {}", json.toString());
        
        // Create the request
        Request request = new Request.Builder()
            .url(translationUrl)
            .post(body)
            .addHeader("Ocp-Apim-Subscription-Key", translatorKeyId)
            .addHeader("Ocp-Apim-Subscription-Region", translatorRegion)
            .addHeader("Content-Type", "application/json")
            .build();
        
        logger.logDebug("Sending translation request to Azure");
        
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            
            if (!response.isSuccessful()) {
                logger.logError("Translation request failed: {}", responseBody);
                throw new IOException("Translation request failed: " + responseBody);
            }
            
            JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();
            String jobId = responseJson.get("id").getAsString();
            logger.logDebug("Translation job created with ID: {}", jobId);
            
            return jobId;
        }
    }

       /**
     * Check the status of a translation job.
     */
    private void checkJobStatus(String jobId) throws IOException, InterruptedException {
        logger.logDebug("Checking status for translation job ID: {}", jobId);
        
        String jobUrl = String.format(translatorEndpoint + "translator/document/batches/%s?api-version=" + apiVersion, jobId);
        
        int maxAttempts = 60; // Maximum number of attempts to check the status
        int delayBetweenAttempts = 10; // Delay in seconds between attempts
        int attempts = 0;
        
        while (attempts++ < maxAttempts) {
            Request request = new Request.Builder()
                .url(jobUrl)
                .get()
                .addHeader("Ocp-Apim-Subscription-Key", translatorKeyId)
                .addHeader("Ocp-Apim-Subscription-Region", translatorRegion)
                .addHeader("Content-Type", "application/json")
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Translation status check failed: " + response.message());
                }
                
                JsonObject responseJson = JsonParser.parseString(response.body().string()).getAsJsonObject();
                String status = responseJson.get("status").getAsString();
                
                logger.logDebug("Current translation status: {} for job ID: {}", status, jobId);
                
                if ("Succeeded".equalsIgnoreCase(status)) {
                    logger.logInfo("Translation completed successfully for job ID: {}", jobId);
                    return;
                } else if ("Failed".equalsIgnoreCase(status) || "ValidationFailed".equalsIgnoreCase(status)) {
                    logger.logError("Translation failed for job ID: {}", jobId);
                    throw new RuntimeException("Translation failed with status: " + status);
                }
                
                TimeUnit.SECONDS.sleep(delayBetweenAttempts);
            }
        }
        
        throw new IOException("Translation timed out after " + maxAttempts + " attempts");
    }

    /**
     * Delete a document from Azure Blob Storage.
     */
    private void deleteDocument(String containerName, String documentName) {
        try {
            BlobClientBuilder blobClientBuilder = new BlobClientBuilder()
                .connectionString(blobConnectionString)
                .containerName(containerName)
                .blobName(documentName);
                
            // Delete the blob
            blobClientBuilder.buildClient().delete();
            logger.logDebug("Document {} deleted successfully from container {}", documentName, containerName);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                logger.logDebug("Document {} not found in container {}. This is expected if the document doesn't exist yet.", 
                    documentName, containerName);
            } else {
                logger.logError("Failed to delete document {} in container {}. Error: {}", 
                    documentName, containerName, e.getMessage());
            }
        }
    }



    /**
     * Process database operations for Backpack document translation.
     */
    private void processBackpackDatabase(int translationDocumentID, int documentID, byte[] translatedData, 
                                        String targetLanguage, String fullLanguage) throws SQLException {
        int translatedScheduleID = -1;
        String scheduleName = "";
        int docPersonID = 0;
        String scheduleAccess = "";
        
        // Get schedule information
        String sql = "SELECT scheduletran.scheduleID, s.schedule_name, d.doc_personID, s.schedule_access " +
                    "FROM document d INNER JOIN [Schedule] s ON d.scheduleID = s.scheduleID " +
                    "LEFT OUTER JOIN Schedule scheduletran ON scheduletran.schedule_name LIKE '%(" + 
                    fullLanguage + "%' + s.schedule_name + '%)%' " +
                    "WHERE doc_id = ?";
                    
        try (PreparedStatement pstmt = backpackConnection.prepareStatement(sql)) {
            pstmt.setInt(1, documentID);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    translatedScheduleID = rs.getInt("scheduleID");
                    scheduleName = rs.getString("schedule_name");
                    docPersonID = rs.getInt("doc_personID");
                    scheduleAccess = rs.getString("schedule_access");
                } else {
                    logger.logError("No schedule found for document ID: {}", documentID);
                    throw new SQLException("No schedule found for document ID: " + documentID);
                }
            }
        }
        
        // Create new schedule if needed
        if (translatedScheduleID == 0) {
            sql = "INSERT INTO [schedule] (schedule_name, schedule_createDate, schedule_modifydate, schedule_access, archived) " +
                 "VALUES(?, GETDATE(), GETDATE(), ?, 1)";
                 
            try (PreparedStatement pstmt = backpackConnection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, "(" + fullLanguage + " version of " + scheduleName + ")");
                pstmt.setString(2, scheduleAccess);
                pstmt.executeUpdate();
                
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        translatedScheduleID = generatedKeys.getInt(1);
                    }
                }
            }
            
            // Create translation text for schedule name
            String tokenUUID = "";
            sql = "{call ccs_dev.CCS_Create_Backpack_TranslationText(?,?,?,?,?)}";
            try (CallableStatement cstmt = campusConnection.prepareCall(sql)) {
                cstmt.setInt(1, translatedScheduleID);    
                cstmt.setString(2, scheduleName);
                cstmt.setString(3, targetLanguage);
                cstmt.setString(4, "Schedule");
                cstmt.registerOutParameter(5, Types.VARCHAR);
                
                cstmt.execute();
                tokenUUID = cstmt.getString(5);
            } catch (SQLException e) {
                logger.logError("Error creating translation text for schedule name: {}", scheduleName, e);
            }
            
            // Get translated schedule name
            sql = "SELECT JSON_VALUE(tt.translationData,'$.keyID')[scheduleID], outputData " +
                  "FROM ccs_dev.CCS_TranslationText tt WHERE completed=1 AND " +
                  "token=TRY_CAST(? AS UNIQUEIDENTIFIER)";
                  
            try (PreparedStatement pstmt = campusConnection.prepareStatement(sql)) {
                pstmt.setString(1, tokenUUID);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        sql = "UPDATE [schedule] SET schedule_name=CONCAT(?, ' ', schedule_name) WHERE scheduleID=?";
                        try (PreparedStatement updateStmt = backpackConnection.prepareStatement(sql)) {
                            updateStmt.setString(1, rs.getString("outputData"));
                            updateStmt.setInt(2, translatedScheduleID);
                            updateStmt.executeUpdate();
                        }
                    }
                }
            }
        } else {
            // Update existing schedule
            sql = "UPDATE [schedule] SET schedule_modifydate=GETDATE(), schedule_access=? WHERE scheduleID=?";
            try (PreparedStatement pstmt = backpackConnection.prepareStatement(sql)) {
                pstmt.setString(1, scheduleAccess);
                pstmt.setInt(2, translatedScheduleID);
                pstmt.executeUpdate();
            }
        }
        
        // Check if document exists
        sql = "SELECT * FROM document WHERE doc_personID=? AND scheduleID=?";
        try (PreparedStatement pstmt = backpackConnection.prepareStatement(sql)) {
            pstmt.setInt(1, docPersonID);
            pstmt.setInt(2, translatedScheduleID);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    // Update existing document
                    sql = "UPDATE document SET doc_object=?, doc_updatedt=GETDATE() WHERE doc_personID=? AND scheduleID=?";
                    try (PreparedStatement updateStmt = backpackConnection.prepareStatement(sql)) {
                        updateStmt.setBytes(1, translatedData);
                        updateStmt.setInt(2, docPersonID);
                        updateStmt.setInt(3, translatedScheduleID);
                        updateStmt.executeUpdate();
                    }
                } else {
                    // Create new document
                    sql = "INSERT INTO document (doc_object, doc_personID, scheduleID, doc_createdt, doc_updatedt) VALUES(?,?,?,GETDATE(),GETDATE())";
                    try (PreparedStatement insertStmt = backpackConnection.prepareStatement(sql)) {
                        insertStmt.setBytes(1, translatedData);
                        insertStmt.setInt(2, docPersonID);
                        insertStmt.setInt(3, translatedScheduleID);
                        insertStmt.executeUpdate();
                    }
                }
            }
        }
        
        // Mark request as completed
       // markRequestCompleted(translationDocumentID, scheduleName + "_" + targetLanguage + ".pdf");
    }

    /**
     * Close resources when done.
     */
    @Override
    public void close() {
        logger.logDebug("Closing Azure document translation service resources");
        
        // OkHttpClient doesn't need explicit closing
        // BlobServiceClient doesn't need explicit closing
        
        logger.logDebug("Azure resources closed successfully");
    }
}