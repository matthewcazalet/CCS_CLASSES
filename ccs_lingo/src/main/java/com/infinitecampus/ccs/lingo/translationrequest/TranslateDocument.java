package com.infinitecampus.ccs.lingo.translationrequest;
/* usage example:
 * try (TranslateDocument translator = new TranslateDocument.Builder()
        .withCampusConnection(campusConnection)
        .withBackpackConnection(backpackConnection)
        .withToken(token)
        .withConfiguration(config)
        .build()) {
    
    // Translate a Campus document
    translator.translateCampusDocument(translationDocumentID, inputFilePath, 
                                      outputFileName, targetLanguage);
    
    // Or translate a Backpack document
    translator.translateBackpackDocument(translationDocumentID, documentID, 
                                        inputFilePath, targetLanguage, fullLanguage);
    
} catch (Exception e) {
    logger.error("Translation failed", e);
}
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import com.infinitecampus.learnerPlanning.DocumentFile;
//import com.infinitecampus.ccs.lingo.CAMPUSTESTING.TranslateTextRequestTEST;
import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.translationprovider.aws.AwsTranslateDocument;
import com.infinitecampus.ccs.lingo.translationprovider.azure.AzureTranslateDocument;
import com.infinitecampus.ccs.lingo.translationprovider.google.GoogleTranslateDocument;
import com.infinitecampus.ccs.lingo.utility.DocumentChangeHistoryManager;
import com.infinitecampus.ccs.lingo.utility.FileUtilityHelper;
import com.infinitecampus.ccs.lingo.utility.LogHelper;
import com.infinitecampus.ccs.lingo.utility.PDFHelper;
import com.infinitecampus.ccs.lingo.prism.TranslateTextRequest;



/**
 * Manages document translation requests using various translation service providers.
 */
public class TranslateDocument implements AutoCloseable{

    private final LogHelper logger;
    
    // SQL Queries
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 serviceAccount, serviceprovider " +
        "FROM [ccs_lng].[CCS_TranslationDocument] td " +
        "INNER JOIN ccs_lng.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 AND token = TRY_CAST(? AS UNIQUEIDENTIFIER)";
    private static final String SQL_GET_TRANSLATIONS =  "{call ccs_lng.CCS_Get_TranslationDocument(?)}";
    private static final String SQL_UPDATE_TRANSLATION = 
       "UPDATE [ccs_lng].CCS_TranslationDocument " +
        "SET [completed] = 1, [completedDate] = GETDATE(), completedName = ?,notes = ? " +
        "WHERE translationDocumentID = ?";
    private static final String SQL_UPDATE_CAMPUS ="{call ccs_lng.CCS_Create_SPED_StapleDocument(?)}";
    private static final String SQL_GET_TRANSLATEDSCHEDULENAME="SELECT JSON_VALUE(tt.translationData,'$.keyID')[scheduleID],outputData FROM ccs_lng.CCS_TranslationText tt "
        +"WHERE completed=1 AND token=TRY_CAST(? AS UNIQUEIDENTIFIER)";
    private static final String SQL_UPDATE_SCHEDULENAME="UPDATE [schedule] SET schedule_name=CONCAT(?,' ',schedule_name) WHERE scheduleID=?";  
    // Instance fields
    private final Connection campusConnection;
    private final Connection backpackConnection;
    private final String authToken;
    private final Configuration config;
    private DocumentTranslationServiceProvider documentTranslationServiceProvider;

    /**
     * Constructor for TranslateDocument
     * @param campusConnection Campus database connection
     * @param backpackConnection Backpack database connection
     * @param token Authentication token
     * @param configuration Configuration settings
     * @throws NoRecordsFoundException if no records are found during initialization
     */
    /**
     * Interface for document translation services
     */
    public interface DocumentTranslationServiceProvider extends AutoCloseable {
        void translateCampusDocument(String inputFilePath, 
                                    String outputFileName, String targetLanguage) 
                                    throws IOException, SQLException;
                                    
                                    
        void translateBackpackDocument(int translationDocumentID, int documentID, 
                                      String inputFilePath, String targetLanguage, 
                                      String fullLanguage) 
                                      throws IOException, SQLException;
                                      
    }

    /**
     * Adapter classes for document translation services
     */
    private class GoogleDocumentTranslationProvider implements DocumentTranslationServiceProvider {
        private final GoogleTranslateDocument translator;

        public GoogleDocumentTranslationProvider(Connection campusConn, Connection backpackConn, String token, Configuration config) {
            logger.logDebug("GoogleDocumentTranslationProvider initialized with Campus connection: {}, token: {}", campusConn, token);
            logger.logDebug("GoogleDocumentTranslationProvider initialized with Backpack connection: {}, token: {}", backpackConn, token);

            this.translator = new GoogleTranslateDocument.Builder()
                .withCampusConnection(campusConn)
                .withBackpackConnection(backpackConn)
                .withToken(token)
                .withConfiguration(config)
                .build();
        }

        @Override
        public void translateCampusDocument(String inputFilePath, 
                                           String outputFileName, String targetLanguage) 
                                           throws IOException, SQLException {
            translator.translateCampusDocument(inputFilePath, outputFileName, targetLanguage);
        }

      @Override
        public void translateBackpackDocument(int translationDocumentID, int documentID, 
                                             String inputFilePath, String targetLanguage, 
                                             String fullLanguage) 
                                             throws IOException, SQLException {
            translator.translateBackpackDocument(translationDocumentID, documentID, inputFilePath, 
                                               targetLanguage, fullLanguage);
        }

        @Override
        public void close() throws Exception {
            if (translator instanceof AutoCloseable) {
                ((AutoCloseable) translator).close();
            }
        }
    }

    private class AzureDocumentTranslationProvider implements DocumentTranslationServiceProvider {
        private final AzureTranslateDocument translator;

        public AzureDocumentTranslationProvider(Connection campusConn, Connection backpackConn, String token, Configuration config) {
            logger.logDebug("AzureDocumentTranslationProvider initialized with Campus connection: {}, token: {}", campusConn, token);
            logger.logDebug("AzureDocumentTranslationProvider initialized with Backpack connection: {}, token: {}", backpackConn, token);

            this.translator= new AzureTranslateDocument.Builder()
            .withCampusConnection(campusConn)
            .withBackpackConnection(backpackConn)
            .withToken(token)
            .withConfiguration(config)
            .build();
            
        }

        @Override
        public void translateCampusDocument(String inputFilePath, 
                                           String outputFileName, String targetLanguage) 
                                           throws IOException, SQLException {
            translator.translateCampusDocument(inputFilePath, outputFileName, targetLanguage);
        }
        
        @Override
        public void translateBackpackDocument(int translationDocumentID, int documentID, 
                                             String inputFilePath, String targetLanguage, 
                                             String fullLanguage) 
                                             throws IOException, SQLException {
            translator.translateBackpackDocument(translationDocumentID, documentID, inputFilePath, 
                                               targetLanguage, fullLanguage);
        }

        @Override
        public void close() throws Exception {
            if (translator instanceof AutoCloseable) {
                ((AutoCloseable) translator).close();
            }
        }
    }

    private class AwsDocumentTranslationProvider implements DocumentTranslationServiceProvider {
        private final AwsTranslateDocument translator;

        public AwsDocumentTranslationProvider(Connection campusConn, Connection backpackConn, String token, Configuration config) {
            logger.logDebug("AwsDocumentTranslationProvider initialized with Campus connection: {}, token: {}", campusConn, token);
            logger.logDebug("AwsDocumentTranslationProvider initialized with Backpack connection: {}, token: {}", backpackConn, token);
//System.out.println("AwsDocumentTranslationProvider initialized with bp connection: " + backpackConn + ", token: " + token);
            this.translator = new AwsTranslateDocument.Builder()
                .withCampusConnection(campusConn)
                .withBackpackConnection(backpackConn)
                .withToken(token)
                .withConfiguration(config)
                .build();
            
        }

        @Override
        public void translateCampusDocument( String inputFilePath, 
                                           String outputFileName, String targetLanguage) 
                                           throws IOException, SQLException {
            translator.translateCampusDocument(inputFilePath, outputFileName, targetLanguage);
        }

        
        @Override
        public void translateBackpackDocument(int translationDocumentID, int documentID, 
                                             String inputFilePath, String targetLanguage, 
                                             String fullLanguage) 
                                             throws IOException, SQLException {
            translator.translateBackpackDocument(translationDocumentID, documentID, inputFilePath, 
                                               targetLanguage, fullLanguage);
        }

        @Override
        public void close() throws Exception {
            if (translator instanceof AutoCloseable) {
                ((AutoCloseable) translator).close();
            }
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

        public TranslateDocument build() throws NoRecordsFoundException {           
            return new TranslateDocument(campusConnection, backpackConnection, token, config);
        }
    }


    private TranslateDocument(Connection campusConnection, Connection backpackConnection, 
                             String token, Configuration configuration) 
                             throws NoRecordsFoundException {
                                        // Initialize logger first
        this.logger = new LogHelper(configuration).createLogger(TranslateDocument.class);
        logger.logDebug("TranslateDocument initialized with Campus connection: {}, token: {}", campusConnection, token);
        logger.logDebug("TranslateDocument initialized with Backpack connection: {}, token: {}", backpackConnection, token);

        logger.logInfo("Initializing TranslateDocument with token: {}", token);
        try{
            if (!Authenticate.isTranslatedDocumentAuthenticated(campusConnection, token)) {
                logger.logError("Authentication failed for token: {}", token);
                throw new SecurityException("Invalid Token. Access Denied.");
            }
        } catch (Exception e) {
            logger.logError("Authentication verification failed", e);
            throw new SecurityException("Authentication process failed", e);
        }

        this.campusConnection = campusConnection;
        this.backpackConnection = backpackConnection;
        this.authToken = token;
        this.config = configuration;   

        try {
            initialize();
            logger.logInfo("TranslateDocument initialization completed successfully for token: {}", token);

        } catch (NoRecordsFoundException e) {
            logger.logError("No Records Found during initialization: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.logError("Initialization error for token {}: {}", token, e.getMessage());
            throw new RuntimeException("Failed to initialize document translation service", e);
        }
    }

    private void initialize() throws SQLException, NoRecordsFoundException {
        logger.logDebug("Fetching translation configuration for token: {}", authToken);
        
        try (PreparedStatement stmt = campusConnection.prepareStatement(SQL_FETCH_CONFIG)) {
            stmt.setString(1, authToken);
          //  System.out.println("SQL_FETCH_CONFIG: " + SQL_FETCH_CONFIG);
          //  System.out.println("Auth Token: " + authToken);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    logger.logError("No configuration found for token: {}", authToken);
                    throw new NoRecordsFoundException("No records found for token: " + authToken);
                }
                String serviceProvider = rs.getString("serviceprovider");
                logger.logInfo("Found service provider: {} for token: {}", serviceProvider, authToken);
                //  System.out.println("Found service provider: " + serviceProvider + " for token: " + authToken);  
                initializeTranslationServiceProvider(serviceProvider);
            }
        }
        logger.logDebug("Document translation service provider fetched from database: {}", authToken);
    }

    private void initializeTranslationServiceProvider(String provider) throws NoRecordsFoundException {
        logger.logDebug("Initializing translation service provider: {}", provider);
        if (provider == null || provider.isEmpty()) {
            logger.logError("Service provider is null or empty for token: {}", authToken);
            throw new NoRecordsFoundException("Service provider is null or empty for token: " + authToken);
        }
        switch (provider.toLowerCase()) {
            case "google":
                documentTranslationServiceProvider = new GoogleDocumentTranslationProvider(campusConnection, backpackConnection, authToken, config);
                break;
            case "azure":
                documentTranslationServiceProvider = new AzureDocumentTranslationProvider(campusConnection, backpackConnection, authToken, config);
                break;
            case "aws":
                documentTranslationServiceProvider = new AwsDocumentTranslationProvider(campusConnection, backpackConnection, authToken, config);
                break;
            default:
                throw new NoRecordsFoundException("Unsupported service provider: " + provider);
        }
        logger.logDebug("Document translation service initialized: {}", provider);
    }

    /**
     * Executes the translation procedure
     */
    public void procedure() throws TranslationException, NoRecordsFoundException {
        logger.logDebug("Starting translation Document procedure for token: {}", authToken);
         
        List<TranslationData> translationsToProcess = new ArrayList<>();

        //fetch all records
        try (CallableStatement stmt = campusConnection.prepareCall(SQL_GET_TRANSLATIONS)) {
            stmt.setString(1, authToken);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    translationsToProcess.add(new TranslationData(rs));
                }
            }
        } catch (SQLException e) {
            logger.logError("Failed to fetch translations: {}", e.getMessage());
            throw new TranslationException("Failed to fetch translations", e);
        }
        if (translationsToProcess.isEmpty()) {
            logger.logWarn("No records found for token: {}", authToken);
            throw new NoRecordsFoundException("No records found for token: " + authToken);
        }
        // Process cached records
        int processedCount = 0;
        int successCount = 0;
        int failureCount = 0;
        for (TranslationData data : translationsToProcess) {
            processedCount++;
            try {
                boolean success = processTranslation(data);
                if (success) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                failureCount++;
                logger.logError("Error processing translation for ID {}: {}", 
                    data.translationDocumentID, e.getMessage());
            }
        }
        logger.logInfo("Translation procedure completed. Total: {}, Success: {}, Failed: {}", 
        processedCount, successCount, failureCount);
    }

    private boolean processTranslation(TranslationData data) {    
        
        logger.logInfo("Processing translation for ID: {}", data.translationDocumentID);
        
        try {
            String requestedDocumentFile = config.getRequestOutputDirectory() + 
                File.separator + data.requestToken + File.separator + data.completedName;
    
            //    System.out.printf("Request document file path: "+ requestedDocumentFile);
            //    System.out.printf("File exists: "+ FileUtilityHelper.fileExists(requestedDocumentFile));
           // logger.logInfo("Request document file path: {}", requestedDocumentFile);
           // logger.logInfo("File exists: {}", FileUtilityHelper.fileExists(requestedDocumentFile));

            String translatedDocumentName = data.completedName.replace(".pdf", "_" + data.language + ".pdf");
    
            if (!FileUtilityHelper.fileExists(requestedDocumentFile)) {
                logger.logError("Original file doesn't exist: {}", requestedDocumentFile);
                return false;
            }            
            //we will only translate documents if we need to - document hasn't been translated before 
            //or if the english version of the document is different than the translated version
            //currently we are doing this for the backpack documents only
            DocumentChangeHistoryManager changeHistoryManager = new DocumentChangeHistoryManager(backpackConnection,Integer.parseInt(data.keyID));
            boolean shouldTranslate = true;
            if (data.type.equalsIgnoreCase("backpack")) {
                shouldTranslate = changeHistoryManager.shouldTranslate(
                    data.fullLanguage, requestedDocumentFile
                );
                if (!shouldTranslate) {
                    data.outcome = "{\"outcome\":\"Translation skipped because original documents have not changed \"}";
                }
                changeHistoryManager.updateChangeHistory(
                    requestedDocumentFile
                );
            }
            System.out.println("btranslate: " + shouldTranslate);
            // Proceed with translation if needed
            if (shouldTranslate) {                
                translateCampusDocument(requestedDocumentFile, translatedDocumentName, data.language);
            }   
            // Update translation status           
            updateTranslationStatus(data.translationDocumentID, translatedDocumentName,data.outcome);

            // Handle based on document type            
            if (data.type.equalsIgnoreCase("backpack")) {
                if(shouldTranslate){                    
                    updateBackpackDocument(
                        data.translationDocumentID, 
                        translatedDocumentName, 
                        Integer.parseInt(data.keyID), 
                        data.fullLanguage, 
                        data.language
                    );
                }
            } else {
                updateCampusDocument(
                    data.translationDocumentID,
                    data.personID, 
                    translatedDocumentName
                    );            
            }
            return true;
        } catch (Exception e) {
            logger.logError("Failed to process translation ID {}: {}", 
                data.translationDocumentID, e.getMessage());
            return false;
        }
        finally{
            logger.logInfo("Processing translation COMPLETED for ID: {}", data.translationDocumentID);
        
        }
    }
        
    
    private void updateBackpackDocument(int translationDocumentID, String translatedName,int DocumentID,String fullLanguage,String targetLanguage) throws SQLException, NoRecordsFoundException {
        logger.logInfo("Processing translation for backpack translation ID: {}", translationDocumentID);
        //String BackpackDocumentDirectory=config.getRequestOutputDirectory() + File.separator + authToken + File.separator + translatedName;
        byte[] pdfTranslatedDoc = FileUtilityHelper.getPDFBytes(config.getRequestOutputDirectory() + File.separator + authToken + File.separator + translatedName);
        int translatedScheduleID = -1; // Default value if not found     
        logger.logInfo("Backpack connection: {}", backpackConnection);
        try (PreparedStatement stmt = backpackConnection.prepareStatement(
            "SELECT scheduletran.scheduleID, s.schedule_name, d.doc_personID, s.schedule_access " +
            "FROM document d " +
            "INNER JOIN [Schedule] s ON d.scheduleID = s.scheduleID " +
            "LEFT OUTER JOIN Schedule scheduletran ON scheduletran.schedule_name LIKE '%(" + fullLanguage + "%' + s.schedule_name + '%)%' " +
            "WHERE doc_id = ?")) {
    
        stmt.setInt(1, DocumentID);
    
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                int personID = rs.getInt("doc_personID");
                String scheduleName = rs.getString("schedule_name");
                String scheduleAccess = rs.getString("schedule_access");
    
                if (rs.getInt("scheduleID") == 0) {
                    String tokenUUID="";
                    // Create new schedule
                    try (PreparedStatement insertSchedule = backpackConnection.prepareStatement(
                            "INSERT INTO [schedule] (schedule_name, schedule_createDate, schedule_modifydate, schedule_access, archived) " +
                            "VALUES (?, GETDATE(), GETDATE(), ?, 1)",
                            java.sql.Statement.RETURN_GENERATED_KEYS)) {
    
                        insertSchedule.setString(1, "(" + fullLanguage + " version of " + scheduleName + ")");
                        insertSchedule.setString(2, scheduleAccess);
                        insertSchedule.executeUpdate();
    
                        try (ResultSet keys = insertSchedule.getGeneratedKeys()) {
                            if (keys.next()) {
                                translatedScheduleID = keys.getInt(1);
                            }
                        }
                    }
    
                    // Translate schedule name
                    try (CallableStatement cstmt = campusConnection.prepareCall("{call ccs_lng.CCS_Create_Backpack_TranslationText(?,?,?,?,?)}")) {
                        cstmt.setInt(1, translatedScheduleID);
                        cstmt.setString(2, scheduleName);
                        cstmt.setString(3, targetLanguage);
                        cstmt.setString(4, "Schedule");
                        cstmt.registerOutParameter(5, Types.VARCHAR);
                        cstmt.execute();
                        
                        tokenUUID=cstmt.getString(5);
                     //   if(config.getDebugMode())
                     //   {                           
                     //       TranslateTextRequestTEST translateTextRequestTEST = new TranslateTextRequestTEST(campusConnection, config.getCampusApplicationName());
                    //        translateTextRequestTEST.translatetextProcedure(tokenUUID);
                    //    }
                    //    else{
                            TranslateTextRequest translateTextRequest=new TranslateTextRequest(campusConnection, config.getCampusApplicationName(),false);
                            translateTextRequest.translatetextProcedure(tokenUUID);
                    //    }
                        //get the updated schedulename
                        


                      // TranslateTextRequest translateText = new TranslateTextRequest(campusConnection, config.getCampusApplicationName());
                      //  translateText.translatetextProcedure();
                    } catch (Exception e) {
                        System.out.println("Error during translation process: " + e.getMessage());
                    }
                    try(CallableStatement cstmt = campusConnection.prepareCall(SQL_GET_TRANSLATEDSCHEDULENAME)){                    
                        cstmt.setString(1, tokenUUID);
                        try(ResultSet rs1 = cstmt.executeQuery()){
                            if(rs1.next()){
                                //String soutputString=rs1.getString("outputData");
                              //  sql="UPDATE [schedule] SET schedule_name=CONCAT('"+rs1.getString("outputData")+"',' ',schedule_name) WHERE scheduleID=?";                           
                              PreparedStatement updateSchedule = backpackConnection.prepareStatement(SQL_UPDATE_SCHEDULENAME); 
                                updateSchedule.setString(1,rs1.getString("outputData"));
                                updateSchedule.setInt(2, translatedScheduleID);
                                updateSchedule.executeUpdate();
                            }
                        }//end try
                    }//end try <-- cstmt is automatically closed here
                        
                } else {
                    translatedScheduleID = rs.getInt("scheduleID");
    
                    try (PreparedStatement updateSchedule = backpackConnection.prepareStatement(
                            "UPDATE [schedule] SET schedule_modifydate=GETDATE(), schedule_access=? WHERE scheduleID=?")) {
                        updateSchedule.setString(1, scheduleAccess);
                        updateSchedule.setInt(2, translatedScheduleID);
                        updateSchedule.executeUpdate();
                    }
                }
    
                // Check if document exists
                try (PreparedStatement checkDoc = backpackConnection.prepareStatement(
                        "SELECT * FROM document WHERE doc_personID=? AND scheduleID=?")) {
    
                    checkDoc.setInt(1, personID);
                    checkDoc.setInt(2, translatedScheduleID);
    
                    try (ResultSet rs2 = checkDoc.executeQuery()) {
                        if (rs2.next()) {
                            try (PreparedStatement updateDoc = backpackConnection.prepareStatement(
                                    "UPDATE document SET doc_object=?, doc_updatedt=GETDATE() WHERE doc_personID=? AND scheduleID=?")) {
    
                                updateDoc.setBytes(1, pdfTranslatedDoc);
                                updateDoc.setInt(2, personID);
                                updateDoc.setInt(3, translatedScheduleID);
                                updateDoc.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement insertDoc = backpackConnection.prepareStatement(
                                    "INSERT INTO document (doc_object, doc_personID, scheduleID, doc_createdt, doc_updatedt) " +
                                    "VALUES (?, ?, ?, GETDATE(), GETDATE())")) {
    
                                insertDoc.setBytes(1, pdfTranslatedDoc);
                                insertDoc.setInt(2, personID);
                                insertDoc.setInt(3, translatedScheduleID);
                                insertDoc.executeUpdate();
                            }
                        }
                    }
                }
    
                System.out.println("Successfully completed translation for translation ID: " + translationDocumentID);
            }
        }
    } catch (SQLException e) {
        System.out.println("Failed to fetch translated schedule ID: " + e.getMessage());
    }
    



    }
    
    private void updateCampusDocument(int translationDocumentID,int personID,String translatedName){
        logger.logDebug("Stapling campus with translationdocument ID: {}", translationDocumentID);
        try (CallableStatement stmt = campusConnection.prepareCall(SQL_UPDATE_CAMPUS)) {
            stmt.setInt(1, translationDocumentID);
            
            int rowsUpdated = stmt.executeUpdate();
            logger.logDebug("Stapling campus with translationdocument ID {} updated successfully", translationDocumentID);
            logger.logDebug("Rows updated: {}", rowsUpdated);
            
            System.out.println("Stapling campus with translationdocument ID "+translationDocumentID+" translationDocumentID");
            System.out.println("Rows updated: "+ rowsUpdated);

            //copy file to special ed directory
            String specialEdDirectory = "";
            if (config.getDebugMode()){//Debug mode is on, we use a copy of the campus method.
                specialEdDirectory=FileUtilityHelper.getFileDir(config.getDocumentFileDirectory(), config.getCampusApplicationName(), "specialed", String.valueOf(personID));
            }
            else{
                specialEdDirectory=DocumentFile.getFileDir(config.getDocumentFileDirectory(), config.getCampusApplicationName(), "specialed", String.valueOf(personID));
            }
            FileUtilityHelper.copyPdfFiles(config.getRequestOutputDirectory() + File.separator + authToken + File.separator + translatedName, specialEdDirectory+File.separator+translatedName);
                //translatedName, specialEdDirectory+File.separator+translatedName);

            logger.logDebug("File Sent to:{} ", specialEdDirectory+File.separator+translatedName);

        } catch (SQLException e) {
            System.out.println("Failed to Staple campus document ID:"+ translationDocumentID+" "+ e.getMessage());
            logger.logError("Failed to Staple campus document ID {}: {}", translationDocumentID, e.getMessage());
        }
        catch(Exception e) {
            System.out.println("Failed to Staple campus document ID:"+ translationDocumentID+" "+ e.getMessage());
            logger.logError("Failed to copy document to special ed directory: {}", e.getMessage());
        }
        
      //  DocumentFile documentFile = new DocumentFile();       
      //  documentFile.getFileDir(app.documentFilePath, appName, "specialed", String.valueOf(translateRequest.PersonID));


    }
       
  /**
     * Mark a translation request as completed.
     */
    private void updateTranslationStatus(int translationID, String completedName, String outcome) throws SQLException {
        logger.logDebug("Updating translation status for ID: {}", translationID);

         try (PreparedStatement stmt = campusConnection.prepareStatement(SQL_UPDATE_TRANSLATION)) {
            if (completedName == null || completedName.trim().isEmpty()) {
                logger.logWarn("No translated document for ID: {}. Setting to null.", translationID);
                stmt.setNull(1, Types.NVARCHAR);
            } else {
                stmt.setString(1, completedName);
            }
            stmt.setString(2, outcome);
            stmt.setInt(3, translationID);

            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                logger.logDebug("Translation ID {} completed successfully", translationID);
            } else {
                logger.logWarn("Translation ID {} not found or not updated", translationID);
            }
        }      
    }
    
    /**
     * Translate a document from Campus.
     */
    private void translateCampusDocument(String inputFilePath, 
                                       String outputFileName, String targetLanguage) 
                                       throws IOException, SQLException, TranslationException {
     //   logger.logInfo("Translating Campus document ID: {} to language: {}", 
       //               translationDocumentID, targetLanguage);
        
        try {
            documentTranslationServiceProvider.translateCampusDocument(
                 inputFilePath, outputFileName, targetLanguage);
        } catch (IOException | SQLException e) {
            logger.logError("Document translation failed: {}", e.getMessage());
            throw new TranslationException("Document translation failed", e);
        }
    }

    /**
     * Translate a document from Backpack.
     */
    
    private void translateBackpackDocument(int translationDocumentID, int documentID, 
                                         String inputFilePath, String targetLanguage, 
                                         String fullLanguage) 
                                         throws IOException, SQLException, TranslationException {
        logger.logInfo("Translating Backpack document ID: {} to language: {}", 
                      documentID, targetLanguage);
        
        try {
            documentTranslationServiceProvider.translateBackpackDocument(
                translationDocumentID, documentID, inputFilePath, targetLanguage, fullLanguage);
        } catch (IOException | SQLException e) {
            logger.logError("Document translation failed: {}", e.getMessage());
            throw new TranslationException("Document translation failed", e);
        }
    }

    @Override
    public void close() throws Exception {
        logger.logDebug("Closing TranslateDocument resources for token: {}", authToken);
        try{
            if (documentTranslationServiceProvider != null) {
                documentTranslationServiceProvider.close();
            }
        } catch (Exception e) {
            logger.logError("Error closing document translation service provider: {}", e.getMessage());
        }
        
        try{
            if(!config.getDebugMode()){        
                //logger.logInfo("NEED TO RESTORE CODE");                    
                 FileUtilityHelper.deleteFolderContents(new File(config.getRequestOutputDirectory() + File.separator + authToken).toPath());
            }
        } catch (Exception e) {
            logger.logError("Error deleting directory: {}", e.getMessage());
        }
        
    }

    private static class TranslationData {
        private final int translationDocumentID;
        private final String requestToken;
        private final String completedName;
        private final String language;
        private final int personID;
        private final String keyID;
        private final String fullLanguage;
        private final String type;
        private String outcome=null;
    
        public TranslationData(ResultSet rs) throws SQLException {
            this.translationDocumentID = rs.getInt("translationDocumentID");
            this.requestToken = rs.getString("requesttoken");
            this.completedName = rs.getString("requestcompletedName");
            this.language = rs.getString("language");
            this.personID = rs.getInt("personID");
            this.keyID = rs.getString("keyID");
            this.fullLanguage = rs.getString("fullLanguage");
            this.type = rs.getString("type");
        }
    }

}