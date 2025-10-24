package com.infinitecampus.ccs.formstapler;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Logger;

/**
 * A service class responsible for the end-to-end process of generating documents.
 * It fetches document metadata from a database, downloads the documents via HTTP,
 * and saves them to the filesystem.
 *
 * This class is designed to be fully decoupled from any web framework (like Servlets).
 * It implements AutoCloseable to ensure its internal HttpClient is always closed properly.
 */
public final class GenerateDocument implements AutoCloseable {

    private static final Logger logger = AppLogger.getLogger(GenerateDocument.class);

    // --- Core Dependencies ---
    private final Connection campusconnection;
    private final String authToken;
    private final Configuration config;
    private final CloseableHttpClient httpClient;
    private final String baseUrl;
    private final String cookieHeader;

    // --- Database Queries ---
    private static final String SQL_GET_DOCUMENTS =
            "SELECT requestID, " +
            "JSON_VALUE(requestData, '$.documentname') AS documentname, " +
            "JSON_VALUE(requestData, '$.path') AS path, " +
            "JSON_VALUE(requestData, '$.keyid') AS keyid, " +
            "personID, " +
            "[type] " +
            "FROM [ccs_dev].[CCS_Request] cr " +
            "INNER JOIN [form] f ON JSON_VALUE(requestData, '$.keyid') = f.formID " +
            "WHERE token = TRY_CAST(? as uniqueidentifier) AND completed = 0";


    private static final String SQL_UPDATE_REQUEST =
        "UPDATE [ccs_dev].[CCS_Request] SET completed = 1, completedDate = GETDATE(), completedName=? WHERE requestID = ?";

    /**
     * Private constructor to enforce object creation through the static factory method.
     *
     * @param connection A valid JDBC connection.
     * @param token The security token identifying the batch of documents to process.
     * @param config The application configuration object.
     * @param baseUrl The base URL of the application 
     * @param cookieHeader An optional string containing cookies for the HTTP request.
     */
    private GenerateDocument(Connection connection, String token, Configuration config, String baseUrl, String cookieHeader) {
        this.campusconnection = connection;
        this.authToken = token;
        this.config = config;
        this.baseUrl = baseUrl;
        this.cookieHeader = cookieHeader; // This can be null.
        this.httpClient = HttpClients.createDefault();
    }

    /**
     * Factory method to create a new instance of the GenerateDocument service.
     * This is the public entry point for creating an object of this class.
     */
    public static GenerateDocument create(Connection connection, String token, Configuration config, String baseUrl, String cookieHeader) {
        return new GenerateDocument(connection, token, config, baseUrl, cookieHeader);
    }

    /**
     * Executes the main document generation procedure.
     * This method orchestrates the entire process from fetching requests to saving files.
     */
    public void procedure() throws Exception {
        logger.info("Starting document generation procedure for token: [{}]", authToken);
        long startTime = System.currentTimeMillis();
        int documentCount = 0;
        int successCount = 0;

        try (PreparedStatement stmt = campusconnection.prepareStatement(SQL_GET_DOCUMENTS)) {
            stmt.setString(1, authToken);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    documentCount++;
                    try {
                        processRequest(rs);
                        successCount++;
                    } catch (Exception e) {
                        // Log the error for the specific document but continue processing the rest.
                        logger.error("Failed to process document for request ID [{}]: {}",
                            rs.getInt("requestID"), e.getMessage(), e);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Database error while retrieving documents for token [{}]: {}", authToken, e.getMessage(), e);
            throw new Exception("Database error: " + e.getMessage(), e); // A DB error is critical, so we stop.
        } finally {
            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("Procedure complete for token: [{}]. Processed {} documents ({} successful) in {} ms",
                authToken, documentCount, successCount, totalTime);
        }
    }

    /**
     * Processes a single document request from the database ResultSet.
     */
    private void processRequest(ResultSet rs) throws Exception {
        int requestID = rs.getInt("requestID");
        String documentName = rs.getString("documentname");
        String path = rs.getString("path");
        String keyId = rs.getString("keyid");
        int personID = rs.getInt("personID");

        logger.info("Processing request ID: {}, document: {}", requestID, documentName);
        
        // 1. Construct the specific output path for this person.
        String personSpecificPath = config.getSpecialEdFolderLocation() + File.separator + personID;

        // 2. Use the FileUtilityHelper to ensure the directory exists.
        FileUtilityHelper.createDirectories(personSpecificPath);

        // 3. Download the document content.
        String documentUrl = constructDocumentUrl(path, keyId, personID);
        byte[] pdfContent = fetchDocumentAsPdf(documentUrl);
        String filename = generateFileName(requestID, documentName);
        
        // 4. Use the FileUtilityHelper to save the file.
        String fullOutputPath = personSpecificPath + File.separator + filename;
        FileUtilityHelper.writeBytesToFile(fullOutputPath, pdfContent);
        logger.info("PDF saved successfully to: {}", fullOutputPath);
        
        // 5. Mark the request as complete in the database.
        updateRequestStatus(requestID, filename);
        
        logger.info("Successfully processed and saved request ID: {}", requestID);
    }

    /**
     * Constructs the full URL needed to download a specific document.
     */
    private String constructDocumentUrl(String path, String keyId, int personID) {
        String finalBaseUrl = this.baseUrl;
        if (finalBaseUrl.endsWith("/")) {
            finalBaseUrl = finalBaseUrl.substring(0, finalBaseUrl.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // Append query parameters correctly, whether the path already has them or not.
        String url = path.contains("?")
            ? finalBaseUrl + path + "&keyid=" + keyId + "&personid=" + personID
            : finalBaseUrl + path + "?keyid=" + keyId + "&personid=" + personID;
        logger.debug("Constructed URL: {}", url);
        return url;
    }

    /**
     * Performs an HTTP GET request to download the document content as a byte array.
     */
    private byte[] fetchDocumentAsPdf(String url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Authorization", "Bearer " + authToken);
        httpGet.setHeader("Accept", "application/pdf");
        
        // Pass along cookies if they were provided.
        if (this.cookieHeader != null) {
            httpGet.setHeader("Cookie", this.cookieHeader);
        }
        
        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            int statusCode = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            
            // Check for a successful response code (200 OK).
            if (statusCode != 200) {
                String responseBody = entity != null ? EntityUtils.toString(entity) : "No Response Body";
                throw new IOException("Failed to fetch document. Status: " + statusCode + ", Response: " + responseBody);
            }
            if (entity == null) {
                throw new IOException("Received an empty response from server for URL: " + url);
            }
            return EntityUtils.toByteArray(entity);
        }
    }

    /**
     * Creates a filesystem-safe and unique filename for the downloaded document.
     */
    private String generateFileName(int requestID, String documentName) {
        // Sanitize the document name to remove characters that are illegal in filenames.
        String cleanName = documentName.replaceAll("[^a-zA-Z0-9.-]", "_");
        
        // Truncate the name to a reasonable length.
        if (cleanName.length() > 50) {
            cleanName = cleanName.substring(0, 50);
        }
        
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return String.format("req_%d_%s_%s.pdf", requestID, cleanName, timestamp);
    }
    
    /**
     * Updates the request record in the database, marking it as completed.
     */
    private void updateRequestStatus(int requestID, String filename) throws SQLException {
        try (PreparedStatement stmt = campusconnection.prepareStatement(SQL_UPDATE_REQUEST)) {
            stmt.setString(1, filename);
            stmt.setInt(2, requestID);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated != 1) {
                // This could indicate a data integrity issue, so log it as a warning.
                logger.warn("Expected to update 1 row for request ID {} but updated {}", requestID, rowsUpdated);
            }
        }
    }
    
    /**
     * Closes the internal HttpClient resource.
     * This method is called automatically when the class is used in a try-with-resources block.
     */
    @Override
    public void close() throws IOException {
        if (this.httpClient != null) {
            this.httpClient.close();
            logger.debug("HttpClient closed successfully.");
        }
    }
}