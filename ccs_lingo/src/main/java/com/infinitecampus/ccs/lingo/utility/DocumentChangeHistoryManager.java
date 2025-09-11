package com.infinitecampus.ccs.lingo.utility;


import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;



import com.infinitecampus.ccs.lingo.settings.Configuration;


public class DocumentChangeHistoryManager {
    private Connection connection;
    private final LogHelper logger;
    private int documentID;

    // SQL Queries
    private static final String SQL_GET_TRANSLATED_DOC= "SELECT scheduletran.scheduleID, s.schedule_name, d.doc_personID, s.schedule_access " +
        "FROM document d " +
        "INNER JOIN [Schedule] s ON d.scheduleID = s.scheduleID " +
        "INNER JOIN Schedule scheduletran ON scheduletran.schedule_name LIKE CONCAT('%',?,'%') " +
        "WHERE doc_id = ?";
    private static final String SQL_FETCH_PREVIOUS_DOCUMENT = "SELECT doc_Previousobject FROM [dbo].[Document_ChangeHistory] WHERE doc_id = ?";
    private static final String SQL_UPSERT_CHANGEHISTORY = "MERGE INTO [dbo].[Document_ChangeHistory] AS Target " +
        "USING (SELECT ? AS doc_id, ? AS doc_Previousobject) AS Source " +
        "ON Target.doc_ID = Source.doc_id  " +
        "WHEN MATCHED THEN " +
        "    UPDATE SET " +
        "        change_date = GETDATE(), " +
        "        doc_id = Source.doc_id, " +
        "        doc_Previousobject = Source.doc_Previousobject " +
        "WHEN NOT MATCHED THEN " +
        "    INSERT (change_date, doc_id, doc_Previousobject) " +
        "    VALUES (GETDATE(), Source.doc_id, Source.doc_Previousobject);";
    public DocumentChangeHistoryManager(Connection connection, int documentID) {
        logger = new LogHelper(Configuration.getInstance()).createLogger(DocumentChangeHistoryManager.class);
        this.connection = connection;
        this.documentID = documentID;
     
    }

    /**
     * Checks if translation is needed by examining document change history
     * @param fullLanguage Language for the document
     * @param requestedDocumentFile Path to the current document
     * @return boolean indicating if translation is required
     */
    public boolean shouldTranslate(String fullLanguage,String requestedDocumentFile) {
        //if there is no translated document, translation is needed
       try {
            if(!GetTranslatedDocument(fullLanguage)){     
              // System.out.println("^^^^^^HIT1");       
                return true;
            }
       } catch (SQLException e) {
            logger.logError("Error retrieving translated document: {}", e.getMessage());
            return true;
       }

        byte[] pdfPreviousDoc = retrievePreviousDocumentBytes();
        
        // If no previous document exists, translation is needed
        if (pdfPreviousDoc == null) {
           // System.out.println("^^^^^^HIT2");     
            return true;
        }
     


        // Compare current document with previous document
        try {
            return !PDFHelper.comparePdfText(
                FileUtilityHelper.getPDFBytes(requestedDocumentFile), 
                pdfPreviousDoc
            );
        } catch (Exception e) {
            logger.logError("Error comparing PDF documents: {}", e.getMessage());
            return true; // Default to translating if comparison fails
        }
    }

    /**
     * Retrieves previous document bytes from change history
     * @param keyID Document key ID
     * @return byte array of previous document or null
     */
    private byte[] retrievePreviousDocumentBytes() {
        byte[] pdfPreviousDoc = null;
        
        try (PreparedStatement stmt = connection.prepareStatement(SQL_FETCH_PREVIOUS_DOCUMENT)) {
            
            stmt.setInt(1, documentID);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Blob blob = rs.getBlob("doc_Previousobject");
                    if (blob != null) {
                        pdfPreviousDoc = blobToByteArray(blob);
                    }
                }
            }
        } catch (SQLException e) {
            logger.logError("Failed to retrieve previous document: {}", e.getMessage());
        }
        
        return pdfPreviousDoc;
    }
    private boolean GetTranslatedDocument(String fullLanguage) throws SQLException{

        try (PreparedStatement stmt = connection.prepareStatement(SQL_GET_TRANSLATED_DOC)) {
            stmt.setString(1,fullLanguage);
            stmt.setInt(2, documentID);
            
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
                
            }
        }
    }


    /**
     * Converts Blob to byte array
     * @param blob Input Blob
     * @return byte array representation of the blob
     */
    private byte[] blobToByteArray(Blob blob) throws SQLException {
        if (blob == null) return null;
        
        try (InputStream inputStream = blob.getBinaryStream()) {
            long blobLength = blob.length();
            byte[] bytes = new byte[(int) blobLength];
            
            int bytesRead;
            int offset = 0;
            while (offset < bytes.length && 
                   (bytesRead = inputStream.read(bytes, offset, bytes.length - offset)) != -1) {
                offset += bytesRead;
            }
            
            return bytes;
        } catch (IOException e) {
            logger.logError("Error converting blob to byte array: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Updates or inserts a change history record
     * @param keyID Document key ID
     * @param requestedDocumentFile Path to the current document
     */
    public void updateChangeHistory(String requestedDocumentFile) {
        try (PreparedStatement updateStmt = connection.prepareStatement(SQL_UPSERT_CHANGEHISTORY)) {
            
            updateStmt.setInt(1, documentID);                                                    
            updateStmt.setBytes(2, FileUtilityHelper.getPDFBytes(requestedDocumentFile));                   
            
            updateStmt.executeUpdate();                                                
        } catch (SQLException e) {
            logger.logError("Failed to update change history: {}", e.getMessage());
        }
    }
}
