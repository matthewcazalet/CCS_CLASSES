package com.infinitecampus.ccs.lingo.utility;
/*EXAMPLE USAGE... 
 * try (GenerateDocument generator = new GenerateDocument.Builder()
        .withConnection(connection)
        .withBackpackConnection(backpackConnection)
        .withToken(token)
        .withConfiguration(config)
        .build()) {
    
    generator.procedure();
    
} catch (Exception e) {
    logger.error("Document generation failed", e);
}
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;

public class GenerateDocument implements AutoCloseable {
    private static final LogHelper logger = new LogHelper(Configuration.getInstance()).createLogger(GenerateDocument.class);
    
    // SQL Queries
    private static final String SQL_FETCH_CONFIG = "SELECT TOP 1 crc.username,crc.password,crc.url,crc.[encryptionversion] "+
        "FROM [ccs_dev].[CCS_Request] cr INNER JOIN [ccs_dev].[CCS_RequestConfig]  crc ON cr.requestconfigID=crc.requestconfigID "+
        "WHERE cr.completed=0 AND cr.token = TRY_CAST(? AS UNIQUEIDENTIFIER)";
    
    private static final String SQL_GET_DOCUMENTS = "SELECT requestID, JSON_VALUE(requestData, '$.documentname')[documentname], " +
        "JSON_VALUE(requestData, '$.path')[path], JSON_VALUE(requestData, '$.keyid')[keyid], " +
        "[type] FROM [ccs_dev].[CCS_Request] WHERE token = TRY_CAST(? as uniqueidentifier) AND completed=0";
    
    private static final String SQL_UPDATE_REQUEST = "UPDATE [ccs_dev].CCS_Request " +
        "SET [completed] = CASE WHEN ? = 'ERROR' THEN 0 ELSE 1 END, " +
        "[completedDate] = CASE WHEN ? = 'ERROR' THEN NULL ELSE GETDATE() END, " +
        "completedName = ? " +
        "WHERE requestID = ?";

    // Instance fields
    private CloseableHttpClient httpClient;
    private CookieStore cookieStore;
    private HttpClientContext context;
    private String baseUrl;
    
    private final Connection Campusconnection;
    private final Connection backpackConnection;
    private final String authToken;
    private final String outputFileLocation;
    private final Configuration config;

    /**
     * Custom exceptions
     */
    public static class GenerateException extends Exception {
        public GenerateException(String message, Throwable cause) {
            super(message, cause);
        }
        public GenerateException(String message) {
            super(message);
        }
    }

    public static class NoRecordsFoundException extends Exception {
        public NoRecordsFoundException(String message) {
            super(message);
        }
    }

    /**
     * Builder pattern implementation for GenerateDocument
     */
    public static class Builder {
        private Connection connection;
        private Connection backpackConnection;
        private String token;
        private Configuration config;

        public Builder withConnection(Connection connection) {           
            this.connection = connection;
            return this;
        }
        
        public Builder withBackpackConnection(Connection backpackConnection) {           
            this.backpackConnection = backpackConnection;
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

        public GenerateDocument build() throws NoRecordsFoundException {
            if (connection == null) {
                throw new IllegalStateException("Database connection is required");
            }
            
            if (token == null || token.trim().isEmpty()) {
                throw new IllegalStateException("Authentication token is required");
            }
            
            if (config == null) {
                throw new IllegalStateException("Configuration is required");
            }
            
            return new GenerateDocument(connection, backpackConnection, token, config);
        }
    }
    private GenerateDocument(Connection connection, Connection backpackconnection, String token, Configuration configuration) throws NoRecordsFoundException {
        logger.logInfo("Initializing GenerateDocument for token: [{}]", token);

    try {
        if (!Authenticate.isGenerateOutputAuthenticated(connection, token)) {
            logger.logError("Authentication failed for token: [{}]", token);
            throw new SecurityException("Invalid Token. Access Denied.");
        }
        
        this.Campusconnection = connection;
        this.backpackConnection = backpackconnection;
        this.authToken = token;
        this.config = configuration;

        this.outputFileLocation = config.getRequestOutputDirectory() + File.separator + authToken;
        
        logger.logDebug("Setting up HTTP client components");
        this.cookieStore = new BasicCookieStore();
        this.context = HttpClientContext.create();
        this.context.setCookieStore(cookieStore);
        this.httpClient = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .build();

        initialize();
        logger.logInfo("Successfully initialized GenerateDocument instance");
        
    } catch (SecurityException e) {
        logger.logError("Security validation failed for token: [{}]", token);
        throw e;
    } catch (Exception e) {
        logger.logError("Initialization failed for token [{}]: {}", token, e.getMessage(), e);
        throw new RuntimeException("Failed to initialize document generation service", e);
    }
}

private void initialize() throws SQLException, NoRecordsFoundException, GenerateException {
    logger.logDebug("Starting initialization process");

    try (PreparedStatement stmt = Campusconnection.prepareStatement(SQL_FETCH_CONFIG)) {
        stmt.setString(1, authToken);
        logger.logDebug("Executing config fetch query for token: [{}]", authToken);
        
        try (ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                logger.logError("No configuration records found for token: [{}]", authToken);
                throw new NoRecordsFoundException("No records found for token: " + authToken);
            }              

            String loginPageUrl = rs.getString("url");            
            int lastSlashIndex = loginPageUrl.lastIndexOf('/');
            baseUrl = loginPageUrl.substring(0, lastSlashIndex + 1); 
            
            logger.logDebug("Base URL configured: [{}]", baseUrl);
            String username = rs.getString("username");
            String password = rs.getString("encryptionversion").equals("1")?PasswordEncryptionUtility.decrypt(rs.getString("password")):rs.getString("password");
            
            logger.logDebug("Attempting login with username: [{}]", username);
            login(username, password);
            
            try(PreparedStatement pstmt = Campusconnection.prepareStatement("UPDATE [ccs_dev].[CCS_RequestConfig] SET [encryptionversion]=1,[password]=? WHERE [encryptionversion]=0")){
                    pstmt.setString(1,PasswordEncryptionUtility.encrypt(password));
                    pstmt.executeUpdate();
            }
        }
       

        logger.logDebug("Successfully completed initialization");
        
    } catch (SQLException e) {
        logger.logError("Database error during initialization: {}", e.getMessage(), e);
        throw new GenerateException("SQL error during initialization: " + e.getMessage(), e);
    } catch (NoRecordsFoundException e) {
        logger.logError("No records found during initialization: {}", e.getMessage());
        throw e;
    } catch (Exception e) {
        logger.logError("Unexpected error during initialization: {}", e.getMessage(), e);
        throw new GenerateException("General error during initialization: " + e.getMessage(), e);
    }      
}
private String cleanUrl(String url) {
    if (url == null || url.trim().isEmpty()) {
        throw new IllegalArgumentException("URL cannot be null or empty");
    }

    try {
        String protocol = "";
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.startsWith("https://")) {
            protocol = "https://";
            url = url.substring(8); // Remove https://
        } else if (lowerUrl.startsWith("http://")) {
            protocol = "http://";
            url = url.substring(7); // Remove http://
        }

        // Clean up multiple slashes in the rest of the URL, but not in protocol
        String cleanedUrl = url.replaceAll("//+", "/");

        // Remove any leading or trailing slashes
        cleanedUrl = cleanedUrl.replaceAll("^/+|/+$", "");

        // Ensure proper URL construction
        String finalUrl = protocol + cleanedUrl;

        // Validate the final URL
        new java.net.URL(finalUrl);

        return finalUrl;
    } catch (java.net.MalformedURLException e) {
        logger.logError("Invalid URL format: {}", url);
        throw new IllegalArgumentException("Invalid URL format: " + url, e);
    }
}
private void login(String username, String password) throws GenerateException {
    logger.logDebug("Starting login process for user: [{}]", username);

    try {    
        // Ensure baseUrl ends with a slash
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

        // Step 1: Get the login page first to capture any initial cookies/tokens
        String loginPageUrl = normalizedBaseUrl + config.getCampusApplicationName()+".jsp";
        loginPageUrl = cleanUrl(loginPageUrl);
        logger.logDebug("Accessing login page: [{}]", loginPageUrl);      
      
        HttpGet getLoginPage = new HttpGet(loginPageUrl);
        HttpResponse response = httpClient.execute(getLoginPage, context);
        int statusCode = response.getStatusLine().getStatusCode();
        
        logger.logDebug("Login page access status code: [{}]", statusCode);
        EntityUtils.consume(response.getEntity());
        
        // Step 2: Submit login credentials
        String verifyUrl = normalizedBaseUrl + "verify.jsp";
        verifyUrl = cleanUrl(verifyUrl);
        logger.logDebug("Submitting credentials to: [{}]", verifyUrl);
        
        HttpPost loginPost = new HttpPost(verifyUrl);
        
        // Create form parameters
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("appName", config.getCampusApplicationName()));
        params.add(new BasicNameValuePair("screen", ""));
        params.add(new BasicNameValuePair("username", username));
        params.add(new BasicNameValuePair("password", password));
        params.add(new BasicNameValuePair("useCSRFProtection", "true"));
        
        loginPost.setEntity(new UrlEncodedFormEntity(params));
        
        response = httpClient.execute(loginPost, context);
        statusCode = response.getStatusLine().getStatusCode();
        logger.logDebug("Login response status code: [{}]", statusCode);                      
        
        if (statusCode != 200 && statusCode != 302) {
            logger.logError("Login failed with status code: [{}]", statusCode);
            throw new GenerateException("Login Failed with status code: " + statusCode);
        }            
       
        validateLoginCookies();
        
        logger.logInfo("Login successful for user: [{}]", username);
        
    } catch (IOException e) {           
        logger.logError("Login failed for user [{}]: {}", username, e.getMessage(), e);
        throw new GenerateException("Login failed with IO exception: " + e.getMessage(), e);
    } catch (Exception e) {
        logger.logError("Unexpected error during login for user [{}]: {}", username, e.getMessage(), e);
        throw new GenerateException("Login failed with unexpected error: " + e.getMessage(), e);
    }
}
public void procedure() throws GenerateException {
    logger.logInfo("Starting document generation procedure for token: [{}]", authToken);
    long startTime = System.currentTimeMillis();

    // Create the folder if it doesn't exist
    FileUtilityHelper.createFolderIfNotExists(outputFileLocation);
    logger.logDebug("Output directory created/verified: [{}]", outputFileLocation);

    try (PreparedStatement stmt = Campusconnection.prepareStatement(SQL_GET_DOCUMENTS)) {
        stmt.setString(1, authToken);
        logger.logDebug("Executing document query for token: [{}]", authToken);
        
        try (ResultSet rs = stmt.executeQuery()) {
            int documentCount = 0;
            while (rs.next()) {
                documentCount++;
                logger.logDebug("Processing document {} for request ID: [{}]", 
                    documentCount, rs.getInt("requestID"));
                processRequest(rs);
            }
            
            long processingTime = System.currentTimeMillis() - startTime;
            logger.logInfo("Document generation procedure completed. Processed {} documents in {} ms", 
                documentCount, processingTime);
        }
    } catch (SQLException e) {
        logger.logError("Database error during document generation: {}", e.getMessage(), e);
        throw new GenerateException("SQL error during document generation: " + e.getMessage(), e);
    } catch (Exception e) {
        logger.logError("General error during document generation: {}", e.getMessage(), e);
        throw new GenerateException("General error during document generation: " + e.getMessage(), e);
    }            
}

private void processRequest(ResultSet rs) throws GenerateException, SQLException {        
    int requestId = rs.getInt("requestID");
    String documentName = rs.getString("documentname") + "_" + requestId + ".pdf";
    String path = rs.getString("path");
    String type = rs.getString("type");
    
    logger.logInfo("Processing request - ID: [{}], Document: [{}], Type: [{}]", requestId, documentName, type);

    try {
        byte[] pdfContent = null;
        
        if (type.equalsIgnoreCase("backpack")) {
            logger.logDebug("Retrieving Backpack document for request ID: [{}]", requestId);
            pdfContent = retrieveBackpackPDF(rs.getInt("keyid"));
        } else {
            logger.logDebug("Generating Campus document for request ID: [{}]", requestId);
            pdfContent = generateCampusDocument(baseUrl + "/" + path);
        }
       
        if (pdfContent != null) {
            String outputPath = outputFileLocation + File.separator + documentName;
            logger.logDebug("Saving PDF to: [{}]", outputPath);
            
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                fos.write(pdfContent);
                logger.logDebug("Successfully saved PDF, size: {} bytes", pdfContent.length);
            } catch (Exception e) {
                logger.logError("Error saving PDF to file: {}", e.getMessage(), e);
                throw new GenerateException("Error saving PDF to file: " + e.getMessage(), e);
            }
            
            logger.logInfo("Document successfully generated and saved: [{}]", documentName);
            markRequest(requestId, documentName);
            
        } else {
            logger.logError("Failed to generate PDF content for request ID: [{}]", requestId);
            throw new GenerateException("Failed to generate PDF content for request ID: " + requestId);
        }            
        
    } catch (Exception e) {
        logger.logError("Document generation failed for request ID [{}]: {}", requestId, e.getMessage(), e);
        throw new GenerateException("Document generation failed for ID " + requestId + ": " + e.getMessage(), e);
    }
}

private void markRequest(int requestID, String completedName) throws GenerateException {
    logger.logDebug("Marking request as completed - ID: [{}], Name: [{}]", requestID, completedName);
    
    try (PreparedStatement stmt = Campusconnection.prepareStatement(SQL_UPDATE_REQUEST)) {
        stmt.setString(1, completedName);
        stmt.setString(2, completedName);
        stmt.setString(3, completedName);
        stmt.setInt(4, requestID);
        
        int updatedRows = stmt.executeUpdate();
        if (updatedRows > 0) {
            logger.logInfo("Successfully marked request as completed - ID: [{}]", requestID);
        } else {
            logger.logWarn("No rows updated when marking request as completed - ID: [{}]", requestID);
        }
        
    } catch (SQLException e) {
        logger.logError("Database error marking request [{}] as completed: {}", requestID, e.getMessage(), e);
        throw new GenerateException("SQL error during request update: " + e.getMessage(), e);
    } catch (Exception e) {
        logger.logError("Unexpected error marking request [{}] as completed: {}", requestID, e.getMessage(), e);
        throw new GenerateException("General error during request update: " + e.getMessage(), e);
    }
}
private byte[] retrieveBackpackPDF(int docId) throws IOException, SQLException, GenerateException {
    logger.logDebug("Starting Backpack PDF retrieval for document ID: [{}]", docId);
    PreparedStatement stmt = null;
    ResultSet rs = null;    

    if (backpackConnection == null) {
        logger.logError("Backpack connection is null for document ID: [{}]", docId);
        throw new GenerateException("Backpack connection is null. Cannot retrieve PDF.");
    }
    
    if (backpackConnection.isClosed()) {
        logger.logError("Backpack connection is closed for document ID: [{}]", docId);
        throw new GenerateException("Backpack connection is closed. Cannot retrieve PDF.");
    }

    try {
        String sql = "SELECT doc_object FROM [document] WHERE doc_id = ?";
        stmt = backpackConnection.prepareStatement(sql);
        stmt.setInt(1, docId);
        logger.logDebug("Executing Backpack query for document ID: [{}]", docId);
        
        rs = stmt.executeQuery();
        if (rs.next()) {
            logger.logDebug("Record found for Backpack document ID: [{}]", docId);
            Blob blob = rs.getBlob("doc_object");
            
            if (blob != null) {
                logger.logDebug("Retrieved blob of size: {} bytes", blob.length());
                //System.out.println("Blob size: " + blob.length());
                try (InputStream inputStream = blob.getBinaryStream()) {
                    long blobLength = blob.length();
                    byte[] blobData = new byte[(int)blobLength];
            
                    int bytesRead = 0;
                    int offset = 0;
                    while (offset < blobData.length && 
                           (bytesRead = inputStream.read(blobData, offset, blobData.length - offset)) != -1) {
                        offset += bytesRead;
                    }
                    
                    logger.logInfo("Successfully retrieved Backpack PDF, size: {} bytes", offset);
                    return blobData;
                }
            } else {
                logger.logError("Null blob returned for Backpack document ID: [{}]", docId);
                return null;
            }
        } else {
            logger.logError("No record found for Backpack document ID: [{}]", docId);
            return null;
        }
    } catch (SQLException | IOException e) {
        logger.logError("Error retrieving Backpack PDF for document ID [{}]: {}", docId, e.getMessage(), e);
        throw e;
    } finally {
        closeResources(rs, stmt);
    }
}

private byte[] generateCampusDocument(String url) throws IOException, GenerateException {
    logger.logDebug("Starting Campus document generation from URL: [{}]", url);    
    HttpGet httpGet = new HttpGet(url);

    // Add browser-like headers
    httpGet.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/91.0.4472.124");
    httpGet.setHeader("Accept", "application/pdf,application/x-pdf,*/*");

    // Add cookies as headers
    for (Cookie cookie : cookieStore.getCookies()) {
        if (cookie.getName().equals("JSESSIONID") || 
            cookie.getName().equals("XSRF-TOKEN") || 
            cookie.getName().equals("appName")) {
            logger.logDebug("Adding cookie to request: [{}]", cookie.getName());
            httpGet.addHeader("Cookie", cookie.getName() + "=" + cookie.getValue());
        }
    }
    
    String xsrfToken = getXsrfToken();
    if (xsrfToken != null && !xsrfToken.isEmpty()) {
        logger.logDebug("Adding XSRF token to request");
        httpGet.setHeader("X-XSRF-TOKEN", xsrfToken);
    }
    
    HttpResponse response = httpClient.execute(httpGet, context);
    int statusCode = response.getStatusLine().getStatusCode();
    logger.logDebug("Document generation response status: [{}]", statusCode);
 
    if (config.getDebugMode()) {
        logger.logInfo("GET request status code: [{}] for URL: [{}]", statusCode, url);
    }
    
    if (statusCode == 200) {
        HttpEntity entity = response.getEntity();        
        if (entity.getContentType() != null && entity.getContentType().getValue().contains("application/pdf")) {
            byte[] content = EntityUtils.toByteArray(entity);
            logger.logInfo("Successfully generated PDF document, size: {} bytes", content.length);
            return content;
        } else {
            logger.logError("Invalid content type received: [{}]", entity.getContentType().getValue());
            return null;
        } 
    } else {
        logger.logError("Document generation failed with status code: [{}]", statusCode);
        return null;
    }
}

private String getXsrfToken() {
    logger.logDebug("Retrieving XSRF token from cookies");
    for (Cookie cookie : cookieStore.getCookies()) {
        if ("XSRF-TOKEN".equals(cookie.getName())) {
            logger.logDebug("Found XSRF token in cookies");
            return cookie.getValue();
        }
    }
    logger.logDebug("No XSRF token found in cookies");
    return null;
}

private void validateLoginCookies() throws GenerateException {
    logger.logDebug("Validating login cookies");
    boolean hasSessionId = false;
    boolean hasXsrfToken = false;

    for (Cookie cookie : cookieStore.getCookies()) {
        if ("JSESSIONID".equals(cookie.getName())) {
            hasSessionId = true;
            logger.logDebug("Found JSESSIONID cookie");
        }
        if ("XSRF-TOKEN".equals(cookie.getName())) {
            hasXsrfToken = true;
            logger.logDebug("Found XSRF-TOKEN cookie");
        }
    }
    
    if (!hasSessionId) {
        logger.logError("JSESSIONID cookie not found after login");
        throw new GenerateException("Login Failed: JSESSIONID cookie not found");
    }
    
    if (!hasXsrfToken) {
        logger.logError("XSRF-TOKEN cookie not found after login");
        throw new GenerateException("Login Failed: XSRF-TOKEN cookie not found");
    }
    
    logger.logDebug("Login cookie validation successful");
}

public void displayCookies() {
    logger.logDebug("Displaying all cookies");
    List<Cookie> cookies = cookieStore.getCookies();
    if (cookies.isEmpty()) {
        logger.logInfo("No cookies present");
    } else {
        for (Cookie cookie : cookies) {
            logger.logInfo("Cookie: {} = {}", cookie.getName(), cookie.getValue());
            logger.logDebug("Cookie details - Domain: {}, Path: {}, Expires: {}", 
                cookie.getDomain(), cookie.getPath(), cookie.getExpiryDate());
        }
    }
}

private void closeResources(ResultSet rs, PreparedStatement pstmt) {
    logger.logDebug("Closing database resources");
    try {
        if (rs != null) {
            rs.close();
            logger.logDebug("ResultSet closed");
        }
        if (pstmt != null) {
            pstmt.close();
            logger.logDebug("PreparedStatement closed");
        }
    } catch (SQLException e) {
        logger.logError("Error closing database resources: {}", e.getMessage(), e);
    }
}

@Override
public void close() {
    logger.logDebug("Closing GenerateDocument resources");
    try {
        if (httpClient != null) {
            httpClient.close();
            logger.logInfo("Successfully closed HttpClient");
        }
    } catch (IOException e) {
        logger.logError("Error closing HttpClient: {}", e.getMessage(), e);
    }
    try {
        if(!config.getDebugMode()) {
            FileUtilityHelper.deleteFolderContents(new File(outputFileLocation).toPath());
            logger.logInfo("Deleted folder contents at: [{}]", outputFileLocation);
        }
    } catch (IOException e) {
        logger.logError("Error deleting folder contents: {}", e.getMessage(), e);
    } catch (Exception e) {
        logger.logError("Unexpected error deleting folder contents: {}", e.getMessage(), e);
    }

}
}