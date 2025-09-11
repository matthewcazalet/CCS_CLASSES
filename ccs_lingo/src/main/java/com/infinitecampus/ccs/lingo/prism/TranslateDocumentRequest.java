package com.infinitecampus.ccs.lingo.prism;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.infinitecampus.CampusObject;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.translationrequest.TranslateDocument;
import com.infinitecampus.ccs.lingo.utility.GenerateDocument;
import com.infinitecampus.ccs.lingo.utility.LogHelper;



public class TranslateDocumentRequest extends CampusObject {
    private static final String SQL_GET_TRANSLATION_DOCUMENT_BATCH = "{call ccs_dev.CCS_Create_TranslationDocument_Batch(?,?)}";
    private static Timestamp requestTimestamp = new Timestamp(System.currentTimeMillis());


    private static final Logger logger = LogManager.getLogger(TranslateDocumentRequest.class);

    public static String[] translateDocumentProcedureRights = new String[]{"system.ScheduledTask"};
    public static String[] translateDocumentProcedureParams = new String[]{"requestToken"};

     public TranslateDocumentRequest(){      

     }
    public TranslateDocumentRequest(CampusObject co){
        super(co);       
    }
    public TranslateDocumentRequest(Connection con, String appName){
        super(con, appName);
    }
    public void translateDocumentProcedure(String token) throws Exception {
        System.out.println("translateDocumentProcedure Started");
        Configuration config = Configuration.getInstance();
        config.loadConfiguration(con, appName);

        try (CallableStatement stmt = con.prepareCall(SQL_GET_TRANSLATION_DOCUMENT_BATCH)) {
            stmt.setNull(1,java.sql.Types.TIMESTAMP);
            stmt.setString(2,token);
            try (ResultSet rs = stmt.executeQuery()) {
                String outputRequestbatchID;
                String translateDocumentbatchID;
                if (rs.next()) {
                    outputRequestbatchID = rs.getString("requesttoken");
                    System.out.println("Request Token: " + outputRequestbatchID);
                    try (OutputGenerationHandler outputrequesthandler = new OutputGenerationHandler(con, outputRequestbatchID, config)) {
                        outputrequesthandler.generatedocument();
                        System.out.println("Request Token document generation completed successfully.");
                        translateDocumentbatchID = rs.getString("translatetoken");
                        System.out.println("Translation Document batchtoken: " + translateDocumentbatchID);
                        try (TranslationHandler translationHandler = new TranslationHandler(con, translateDocumentbatchID, config)) {
                            translationHandler.translate();
                            System.out.println("Translation Document batch completed successfully.");
                        } catch (Exception e) {
                            System.out.println("Error during translation process: " + e.getMessage());
                            e.printStackTrace();
                            throw e;
                        }
                    } catch (Exception e) {
                        System.out.println("Error during request process: " + e.getMessage());
                        e.printStackTrace();
                        throw e;
                    }
                } else {
                    System.out.println("No Translation Documents Requests at this time.");
                }
            }
        } catch (SQLException se) {
            System.out.println("SQL Error in translateDocumentProcedure: " + se.getMessage());
            se.printStackTrace();
            throw se;
        } catch (Exception e) {
            System.out.println("Exception Error in translateDocumentProcedure: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            CampusObject.close(con);
            System.out.println("translateDocumentProcedure completed.");
        }
    }
    public void translateDocumentProcedure() throws Exception {
        System.out.println("translateDocumentProcedure Started");
        Configuration config = Configuration.getInstance();
        config.loadConfiguration(con, appName);

        try (CallableStatement stmt = con.prepareCall(SQL_GET_TRANSLATION_DOCUMENT_BATCH)) {
            stmt.setTimestamp(1, requestTimestamp);  
            stmt.setNull(2,java.sql.Types.NVARCHAR);       
            try (ResultSet rs = stmt.executeQuery()) {
                String outputRequestbatchID;
                String translateDocumentbatchID;
                if (rs.next()) {
                    outputRequestbatchID = rs.getString("requesttoken");
                    System.out.println("Request Token: " + outputRequestbatchID);
                    try (OutputGenerationHandler outputrequesthandler = new OutputGenerationHandler(con, outputRequestbatchID, config)) {
                        outputrequesthandler.generatedocument();
                        System.out.println("Request Token document generation completed successfully.");
                        translateDocumentbatchID = rs.getString("translatetoken");
                        System.out.println("Translation Document batchtoken: " + translateDocumentbatchID);
                        try (TranslationHandler translationHandler = new TranslationHandler(con, translateDocumentbatchID, config)) {
                            translationHandler.translate();
                            System.out.println("Translation Document batch completed successfully.");
                        } catch (Exception e) {
                            System.out.println("Error during translation process: " + e.getMessage());
                            e.printStackTrace();
                            throw e;
                        }
                    } catch (Exception e) {
                        System.out.println("Error during request process: " + e.getMessage());
                        e.printStackTrace();
                        throw e;
                    }
                } else {
                    System.out.println("No Translation Documents Requests at this time.");
                }
            }
        } catch (SQLException se) {
            System.out.println("SQL Error in translateDocumentProcedure: " + se.getMessage());
            se.printStackTrace();
            throw se;
        } catch (Exception e) {
            System.out.println("Exception Error in translateDocumentProcedure: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
           // CampusObject.close(con);// This is closing the connection too early!
            System.out.println("translateDocumentProcedure completed.");
        }
    }

    private static class OutputGenerationHandler implements AutoCloseable {
        private final LogHelper logger = new LogHelper(Configuration.getInstance()).createLogger(OutputGenerationHandler.class);
        
        private final Connection connection;
        private final String token;
        private final Configuration config;
        private GenerateDocument generateDocument;
        public OutputGenerationHandler(Connection connection, String token, Configuration config) {
            logger.logInfo("Initializing request handler for token: {}", token);         
          

            this.connection = connection;
            this.token = token;
            this.config = config;

            
            
        }
       public void generatedocument() throws Exception {

            try {
                logger.logInfo("Beginning document generation for token: [{}]", token);
    
                generateDocument = new GenerateDocument.Builder()
                    .withConnection(connection)
                    .withBackpackConnection(config.getBackpackConnection())//need to set this to null for now
                    .withToken(token)
                    .withConfiguration(config)
                    .build();
    
                generateDocument.procedure();
    
                logger.logInfo("Document generation successful for token: [{}]", token);
                
            } catch (Exception e) {
                logger.logError("Error during document generation: {}", e.getMessage(), e);
                throw e;
            }
        }
        @Override
        public void close() {
            logger.logInfo("Closing request resources for token: {}", token);
            if (generateDocument != null) {
                try {
                    generateDocument.close();
                } catch (Exception e) {
                    logger.logError("Failed to close GenerateDocument for token: {}", token, e);
                }
            }
        }
        
    }


    private static class TranslationHandler implements AutoCloseable {
        private final LogHelper logger = new LogHelper(Configuration.getInstance()).createLogger(TranslationHandler.class);
        
        private final Connection connection;
        private final String token;
        private final Configuration config;
        private TranslateDocument translateDocument;

        public TranslationHandler(Connection connection, String token, Configuration config) {
            logger.logInfo("Initializing translation handler for token: {}", token);
          //  config.setCampusApplicationName(appName);
         //   config.setCampusApplicationName(TranslateDocumentRequest.appNamex);
            this.connection = connection;
            this.token = token;
            this.config = config;
        }
        public void translate() throws Exception {
            logger.logInfo("Initiating translation process for token: {}", token); 
            try {
                translateDocument=new TranslateDocument.Builder()
                .withCampusConnection(connection)
                .withBackpackConnection(config.getBackpackConnection())
                .withToken(token)
                .withConfiguration(config)
                .build();

                translateDocument.procedure();
                logger.logInfo("Translation completed successfully for token: {}", token);

            } catch (Exception e) {
                logger.logError("TranslationHandler.translate() - Error during translation process", e);
                logger.logInfo("Translation failed for token: {}", token);
                throw e;
            }
        }
        @Override
        public void close() {
            if(connection != null) {
                try {
                    CampusObject.close(connection);
                } catch (Exception e) {
                    logger.logError("Failed to close connection for token: {}", token, e);
                }
            }
            
            logger.logDebug("TranslationHandler.close() - Closing translation resources");
            
            logger.logInfo("Closing translation resources for token: {}", token);
            if (translateDocument != null) {
                try {
                    translateDocument.close();
                } catch (Exception e) {
                    logger.logError("Failed to close TranslateDocument for token: {}", token, e);
                }
            }
        }
    }        
}
