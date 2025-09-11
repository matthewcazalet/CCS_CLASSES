package com.infinitecampus.ccs.lingo.prism;

import com.infinitecampus.CampusObject;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.translationrequest.TranslateText;
import com.infinitecampus.ccs.lingo.utility.LogHelper;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TranslateTextRequest extends CampusObject {
    private static final String SQL_GET_TRANSLATION_TEXT_BATCH = "{call ccs_dev.CCS_Create_TranslationText_Batch(?,?)}";
    private static Timestamp requestTimestamp = new Timestamp(System.currentTimeMillis());
    private static final Logger logger = LogManager.getLogger(TranslateText.class);
    
    private final boolean ownsConnection;

    public static String[] translatetextProcedureRights = new String[]{"system.ScheduledTask"};
    public static String[] translatetextProcedureParams = new String[]{"translatetexttoken"};

// Default constructor
    public TranslateTextRequest() {
        super();
        this.ownsConnection = true;
    }

    // CampusObject constructor
    public TranslateTextRequest(CampusObject co) {
        super(co);
        this.ownsConnection = true;
    }

    // Standard connection constructor (owns connection)
    public TranslateTextRequest(Connection con, String appName) {
        this(con, appName, true);
    }
// New constructor with connection ownership control
public TranslateTextRequest(Connection con, String appName, boolean ownsConnection) {
    super(con, appName);
    this.ownsConnection = ownsConnection;
}
public void translatetextProcedure(String token)throws Exception{
    System.out.println("translatetextProcedure Started");
    try(CallableStatement stmt = con.prepareCall(SQL_GET_TRANSLATION_TEXT_BATCH)){         
        //stmt.setTimestamp(1, requestTimestamp);  
        stmt.setNull(1,java.sql.Types.TIMESTAMP);
        stmt.setString(2,token);
                
        try (ResultSet rs = stmt.executeQuery()) {                  
            String translatetextbatchID;  
            if (rs.next()) {// Check if there are any records returned
                translatetextbatchID=rs.getString("batchid");
                System.out.println("Translation Text batchtoken: " + translatetextbatchID);
               
                try(TranslationHandler translationHandler = new TranslationHandler(con, translatetextbatchID, Configuration.getInstance())) {
                    translationHandler.translate();
                    System.out.println(translatetextbatchID + " Translation Text batch completed successfully.");
                } catch (Exception e) {
                    logger.error("Error during translation process: " + e.getMessage(), e);
                    throw e;
                }

            } else {
                System.out.println("No Translation Text Requests at this time.");
            }      
                            
        }
    }catch(SQLException se){
        logger.error("SQL Error in translatetextProcedure: " + se.getMessage(), se);
        throw se;
    }
    catch(Exception e){
        logger.error("Exception Error in translatetextProcedure: "+e.getMessage(),e);
        throw e;    
    } finally {   
        // Only close the connection if this instance owns it
        if (ownsConnection) {
            try {
                CampusObject.close(con);
            } catch (Exception e) {
                logger.error("Error closing connection: " + e.getMessage(), e);
            }
        }
        System.out.println("translatetextProcedure completed.");
    }
}
public void translatetextProcedure()throws Exception{
    System.out.println("translatetextProcedure Started");
    try(CallableStatement stmt = con.prepareCall(SQL_GET_TRANSLATION_TEXT_BATCH)){         
        stmt.setTimestamp(1, requestTimestamp);  
        stmt.setNull(2,java.sql.Types.NVARCHAR);                    
        try (ResultSet rs = stmt.executeQuery()) {                  
            String translatetextbatchID;  
            if (rs.next()) {// Check if there are any records returned
                translatetextbatchID=rs.getString("batchid");
                System.out.println("Translation Text batchtoken: " + translatetextbatchID);
               
                try(TranslationHandler translationHandler = new TranslationHandler(con, translatetextbatchID, Configuration.getInstance())) {
                    translationHandler.translate();
                    System.out.println(translatetextbatchID + " Translation Text batch completed successfully.");
                } catch (Exception e) {
                    logger.error("Error during translation process: " + e.getMessage(), e);
                    throw e;
                }

            } else {
                System.out.println("No Translation Text Requests at this time.");
            }      
                            
        }
    }catch(SQLException se){
        logger.error("SQL Error in translatetextProcedure: " + se.getMessage(), se);
        throw se;
    }
    catch(Exception e){
        logger.error("Exception Error in translatetextProcedure: "+e.getMessage(),e);
        throw e;    
    } finally {   
        // Only close the connection if this instance owns it
        if (ownsConnection) {
            try {
                CampusObject.close(con);
            } catch (Exception e) {
                logger.error("Error closing connection: " + e.getMessage(), e);
            }
        }
        System.out.println("translatetextProcedure completed.");
    }
}

/**
 * Check connection status
 * */
public boolean isConnectionValid() {
    try {
        return con != null && !con.isClosed() && con.isValid(5); // 5 seconds timeout
    } catch (SQLException e) {
        logger.error("Error checking connection validity: " + e.getMessage(), e);
        return false;
    }
}

    
    private static class TranslationHandler implements AutoCloseable {
        private final LogHelper logger = new LogHelper(Configuration.getInstance()).createLogger(TranslationHandler.class);
        
        private final Connection connection;
        private final String token;
        private final Configuration config;
        private TranslateText translatetext;

        public TranslationHandler(Connection connection, String token, Configuration config) {
             logger.logInfo("Initializing translation handler for token: {}", token);
             this.connection = connection;
             this.token = token;
             this.config = config;
         }
         public void translate() throws Exception {
            logger.logInfo("Initiating translation process for token: {}", token);            
            try {
                translatetext = new TranslateText.Builder()
                    .withConnection(connection)
                    .withToken(token)
                    .withConfiguration(config)
                    .build();
                
                translatetext.procedure();
                logger.logInfo("Translation completed successfully for token: {}", token);
            } catch (Exception e) {
                logger.logError("TranslationHandler.translate() - Translation failed", e);
                logger.logInfo("Translation failed for token: {}", token);
                throw e;
            }
         }
        @Override
        public void close() throws Exception {
            logger.logInfo("Closing translation resources for token: {}", token);
            if (translatetext != null) {
                try {
                    translatetext.close();
                    } catch (Exception e) {
                        logger.logError("TranslationHandler.close() - Error closing translation resources", e);
                        logger.logInfo("Failed to close translation resources for token: {}", token);
                        throw e;
                    }
                }
           }
    }
}
