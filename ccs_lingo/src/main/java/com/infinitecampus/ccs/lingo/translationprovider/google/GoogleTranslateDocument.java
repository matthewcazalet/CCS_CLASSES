package com.infinitecampus.ccs.lingo.translationprovider.google;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.utility.LogHelper;
import com.infinitecampus.ccs.lingo.utility.FileUtilityHelper;

import com.google.cloud.translate.v3.*;
import com.google.protobuf.ByteString;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

/**
 * Handles document translation using Google Cloud Translation API.
 */
public class GoogleTranslateDocument implements AutoCloseable {
    // Initialize logger as static final
    private static final LogHelper logger;
    
    // Static initialization block
    static {
        try {
            logger = new LogHelper(Configuration.getInstance()).createLogger(GoogleTranslateDocument.class);
        } catch (Exception e) {
            // If logger initialization fails, throw runtime exception
            throw new ExceptionInInitializerError("Failed to initialize logger: " + e.getMessage());
        }
    }

    // SQL Queries
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 JSON_VALUE(serviceAccount,'$.project_id') Google_projectID, " +
        "[serviceAccount] " +
        "FROM [ccs_dev].[CCS_TranslationDocument] td " +
        "INNER JOIN ccs_dev.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 AND token = TRY_CAST(? AS UNIQUEIDENTIFIER) " +
        "AND serviceprovider='Google'";

    // Instance fields
    private final Connection campusConnection;
    private final Connection backpackConnection;
    private final String authToken;
    private final Configuration config;
    private String outputFileLocation;
    
    // Google services
    private TranslationServiceClient translationClient;
    private LocationName parentLocation;
    
    // Google configuration
    private String projectId;
    private String serviceAccountJson;

    /**
     * Custom exception for when no translation records are found.
     */
    public static class NoRecordsFoundException extends Exception {
        public NoRecordsFoundException(String message) {
            super(message);
        }
    }

    /**
     * Builder class for GoogleTranslateDocument.
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

        public GoogleTranslateDocument build() throws SecurityException {
            logger.logInfo("Building GoogleTranslateDocument with token: {}", token);
            return new GoogleTranslateDocument(campusConnection, backpackConnection, token, config);
        }
    }

    /**
     * Private constructor - use Builder to create instances.
     */
    private GoogleTranslateDocument(Connection campusConnection, Connection backpackConnection, String token, Configuration configuration) {
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
            throw new RuntimeException("Failed to initialize Google translation service", e);
        }
    }

    /**
     * Initialize the Google translation service.
     */
    private void initialize() throws SQLException, NoRecordsFoundException, IOException {
        logger.logDebug("Initializing Google document translation service");
        fetchConfigInfo();
        initializeTranslationServices();
        logger.logDebug("Initialization completed successfully");
    }

    /**
     * Fetch configuration information from the database.
     */
    private void fetchConfigInfo() throws NoRecordsFoundException, SQLException {
        logger.logDebug("Fetching configuration information");
        try (PreparedStatement pstmt = campusConnection.prepareStatement(SQL_FETCH_CONFIG)) {
            pstmt.setString(1, authToken);
            logger.logDebug("Executing query with token: {}", authToken);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    logger.logWarn("No records found for token: {}", authToken);
                    throw new NoRecordsFoundException("No records found for token: " + authToken);
                }
                
                // Set Google configuration
                projectId = rs.getString("Google_projectID");
                serviceAccountJson = rs.getString("serviceAccount");
                
                // Setup output directory
                outputFileLocation = config.getRequestOutputDirectory() + File.separator + authToken;
                FileUtilityHelper.createFolderIfNotExists(outputFileLocation);
                
                logger.logDebug("Configuration fetched successfully");
            }
        }
    }

    /**
     * Initialize Google translation services.
     */
    private void initializeTranslationServices() throws IOException {
        logger.logDebug("Initializing translation services");
        
        try (InputStream serviceAccountStream = 
            new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))) {
            
            // Create credentials from the service account JSON
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
            
            // Create translation service settings
            TranslationServiceSettings settings = TranslationServiceSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();
                
            // Create translation client
            translationClient = TranslationServiceClient.create(settings);
            
            // Set parent location
            parentLocation = LocationName.of(projectId, "global");
            
            logger.logDebug("Translation services initialized");
        }
    }

    /**
     * Translate a document from Campus.
     */
    public void translateCampusDocument(String inputFilePath, 
                                        String outputFileName, String targetLanguage) throws IOException, SQLException {
       // logger.logInfo("Processing Campus document translation for ID: {}", translationDocumentID);
        
        try {
            byte[] pdfData = getPdfBytes(inputFilePath);
            byte[] translatedData = translatePdf(pdfData, targetLanguage);

            String outputFilePath = outputFileLocation + File.separator + outputFileName;
            logger.logDebug("Output file path: {}", outputFilePath);
            
            // Ensure the output file does not already exist
            FileUtilityHelper.deleteFileIfExists(outputFilePath);
            
            // Write the translated content to the output location
            FileUtilityHelper.WriteFilesOut(outputFilePath, translatedData);
            
           // markRequestCompleted(translationDocumentID, FileUtilityHelper.getFileName(outputFilePath));
          //  logger.logInfo("Successfully completed translation for ID: {}", translationDocumentID);
        } catch (IOException e) {
            //logger.logError("Error during document translation for ID: {}", translationDocumentID, e);
            throw new IOException("Error during translation process", e);
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
            byte[] pdfData = getPdfBytes(inputFilePath);
            byte[] translatedData = translatePdf(pdfData, targetLanguage);
            
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
     * Translate PDF document using Google Cloud Translation API.
     */
    private byte[] translatePdf(byte[] pdfData, String targetLanguage) throws IOException {
        logger.logDebug("Translating PDF document to language: {}", targetLanguage);
        
        // Create document input configuration
        DocumentInputConfig docInputConfig = DocumentInputConfig.newBuilder()
            .setContent(ByteString.copyFrom(pdfData))
            .setMimeType("application/pdf")
            .build();
            
        // Create document output configuration
        DocumentOutputConfig docOutputConfig = DocumentOutputConfig.newBuilder()
            .setMimeType("application/pdf")
            .build();
            
        // Create translation request
        TranslateDocumentRequest request = TranslateDocumentRequest.newBuilder()
            .setParent(parentLocation.toString())
            .setTargetLanguageCode(targetLanguage)
            .setDocumentInputConfig(docInputConfig)
            .setDocumentOutputConfig(docOutputConfig)
            .setIsTranslateNativePdfOnly(true)
            .setEnableShadowRemovalNativePdf(false)
            .build();
            
        // Execute translation request
        TranslateDocumentResponse response = translationClient.translateDocument(request);
        
        // Get translated content
        ByteString translatedContent = response.getDocumentTranslation().getByteStreamOutputs(0);
        
        logger.logDebug("PDF document translation completed successfully");
        return translatedContent.toByteArray();
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
        if (translationClient != null) {
            try {
                translationClient.close();
                logger.logDebug("Translation client closed successfully");
            } catch (Exception e) {
                logger.logWarn("Error closing translation client", e);
            }
        }
    }
}
        