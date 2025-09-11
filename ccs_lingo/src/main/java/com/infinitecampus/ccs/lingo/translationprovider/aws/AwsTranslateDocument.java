package com.infinitecampus.ccs.lingo.translationprovider.aws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.awt.Color;
import java.awt.image.BufferedImage;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.infinitecampus.ccs.lingo.authenticate.Authenticate;
import com.infinitecampus.ccs.lingo.settings.Configuration;
import com.infinitecampus.ccs.lingo.utility.LogHelper;
import com.infinitecampus.ccs.lingo.utility.FileUtilityHelper;


import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.translate.TranslateClient;

import software.amazon.awssdk.services.textract.model.*;
import software.amazon.awssdk.services.translate.model.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.FontCache;
import org.apache.pdfbox.pdmodel.font.FontInfo;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Handles document translation using AWS Translate and Textract services.
 */
public class AwsTranslateDocument implements AutoCloseable {
    // Initialize logger as static final
    private static final LogHelper logger;
    
    // Static initialization block
    static {
        try {
            logger = new LogHelper(Configuration.getInstance()).createLogger(AwsTranslateDocument.class);
        } catch (Exception e) {
            // If logger initialization fails, throw runtime exception
            throw new ExceptionInInitializerError("Failed to initialize logger: " + e.getMessage());
        }
    }

    // Constants
    private static final Region AWS_REGION = Region.US_EAST_2;
    private static final String DEFAULT_SOURCE_LANGUAGE = "en";
    private static final String FONT_URL = "https://github.com/googlefonts/noto-fonts/raw/main/hinted/ttf/NotoSans/NotoSans-Regular.ttf";
    //"https://github.com/googlefonts/noto-fonts/raw/main/hinted/ttf/NotoSans/NotoSans-Regular.ttf";
    private static final String SQL_FETCH_CONFIG = 
        "SELECT TOP 1 JSON_VALUE(serviceAccount,'$.access_key') [access_key], " +
        "JSON_VALUE(serviceAccount,'$.secret_key') [secret_key], " +
        "serviceprovider, [serviceAccount] " +
        "FROM [ccs_dev].[CCS_TranslationDocument] td " +
        "INNER JOIN ccs_dev.CCS_TranslationConfig tc ON td.translationConfigID = tc.translationConfigID " +
        "WHERE tc.active = 1 AND completed = 0 AND serviceprovider='aws' " +
        "AND token = TRY_CAST(? AS UNIQUEIDENTIFIER);";

    // Instance fields
    private final Connection campusConnection;
    private final Connection backpackConnection;
    private final String authToken;
    private final Configuration config;
    private String outputFileLocation;
    
    // AWS services
    private TextractClient awsTextractClient;
    private TranslateClient awsTranslateClient;
    private AwsConfiguration awsConfig;
    
    // PDF processing
    private PDDocument translatedDocument;
    private PDFont unicodeFont;
    private boolean isUnicodeFontAvailable = false;

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
 * Constants for font handling
 */
private static class FontConstants {
    // Primary font URL from Google Fonts
    static final String PRIMARY_FONT_URL = "https://github.com/googlefonts/noto-fonts/raw/main/hinted/ttf/NotoSans/NotoSans-Regular.ttf";
    
    // Backup font URLs in case primary fails
    static final String[] BACKUP_FONT_URLS = {
        "https://raw.githubusercontent.com/googlefonts/noto-fonts/main/hinted/ttf/NotoSans/NotoSans-Regular.ttf",
        "https://fonts.gstatic.com/s/notosans/v28/o-0IIpQlx3QUlC5A4PNr5TRA.woff2"
    };

    // Local font path in resources
    static final String LOCAL_FONT_RESOURCE = "/fonts/NotoSans-Regular.ttf";
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
     * Helper class to store text line information.
     */
    private static class TextLine {
        public double left;
        public double top;
        public double width;
        public double height;
        public String translatedText;
        public String originalText;

        public TextLine(double left, double top, double width, double height, String originalText, String translatedText) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.originalText = originalText;
            this.translatedText = translatedText;
        }
    }

    /**
     * Helper class to store font information.
     */
    private static class FontInfo {
        int fontSize;
        float textHeight;
        float textWidth;
    }
        /**
     * Builder class for AwsTranslateDocument.
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

        public AwsTranslateDocument build() throws SecurityException {
            logger.logInfo("Building AwsTranslateDocument with token: {}", token);
            return new AwsTranslateDocument(campusConnection, backpackConnection, token, config);
        }
    }

    /**
     * Private constructor - use Builder to create instances.
     */
    private AwsTranslateDocument(Connection campusConnection, Connection backpackConnection, String token, Configuration configuration) {
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
            throw new RuntimeException("Failed to initialize AWS translation service", e);
        }
    }

    /**
     * Initialize the AWS translation service.
     */
    private void initialize() throws SQLException, NoRecordsFoundException, IOException {
        logger.logDebug("Initializing AWS document translation service");
        fetchConfigInfo();
        initializeTranslationServices();
        initializeFont();
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
                
                awsConfig = new AwsConfiguration(
                    rs.getString("access_key"),
                    rs.getString("secret_key"),
                    AWS_REGION
                );
                
                // Setup output directory
                outputFileLocation = config.getRequestOutputDirectory() + File.separator + authToken;
                FileUtilityHelper.createFolderIfNotExists(outputFileLocation);
                
                logger.logDebug("Configuration fetched successfully");
            }
        }
    }
        /**
     * Initialize AWS translation services.
     */
    private void initializeTranslationServices() throws IOException {
        logger.logDebug("Initializing translation services");
        
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
            awsConfig.accessKey,
            awsConfig.secretKey
        );

        awsTextractClient = TextractClient.builder()
            .region(awsConfig.region)
            .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
            .build();
            
        awsTranslateClient = TranslateClient.builder()
            .region(awsConfig.region)
            .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
            .build();
            
        translatedDocument = new PDDocument();
        
        logger.logDebug("Translation services initialized");
    }
/**
 * Initializes the font system for document translation.
 * Attempts to load Unicode font with retry mechanism and fallback options.
 */
private void initializeFont() {
    int maxRetries = 3;
    boolean fontInitialized = false;
    
    for (int attempt = 1; attempt <= maxRetries && !fontInitialized; attempt++) {
        try {
            logger.logDebug("Initializing Unicode font for document translation (Attempt {}/{})", attempt, maxRetries);
            
            File tempFont = File.createTempFile("NotoSans_" + System.currentTimeMillis(), ".ttf");
            tempFont.deleteOnExit();

            URL fontUrl = new URL(FONT_URL);
            downloadFontWithVerification(fontUrl, tempFont);

            if (tempFont.length() > 0) {
                try (FileInputStream fis = new FileInputStream(tempFont)) {
                    unicodeFont = PDType0Font.load(translatedDocument, fis);
                    if (verifyFont(unicodeFont)) {
                        isUnicodeFontAvailable = true;
                        fontInitialized = true;
                        logger.logInfo("Successfully loaded Unicode font");
                        return; // Exit method on successful initialization
                    }
                }
            }
        } catch (Exception e) {
            logger.logWarn("Unicode font initialization attempt {} failed: {}", attempt, e.getMessage());
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000 * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    // If we get here, Unicode font initialization failed
    logger.logWarn("Unicode font initialization failed after {} attempts. Switching to basic font...", maxRetries);
    fallbackToBasicFont();
    logger.logInfo("Successfully initialized fallback font. Translation will continue with limited character support.");
}
/**
 * Verifies that the font can handle both ASCII and Unicode characters.
 * @param font The font to verify
 * @return true if font passes verification, false otherwise
 */
private boolean verifyFont(PDFont font) {
    try {
        // Test string contains both ASCII and Unicode characters
        String testString = "Hello世界";
        font.encode(testString);
        return true;
    } catch (Exception e) {
        logger.logWarn("Font verification failed: {}", e.getMessage());
        return false;
    }
}
/**
 * Downloads the font file with verification checks.
 * @param url The URL to download the font from
 * @param destination The file to save the font to
 * @throws IOException if download fails or verification fails
 */
private void downloadFontWithVerification(URL url, File destination) throws IOException {
    // Setup connection with reasonable timeouts
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(5000);

    // Download with buffered streams for better performance
    try (InputStream in = new BufferedInputStream(connection.getInputStream());
         OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
        
        byte[] buffer = new byte[8192];
        int bytesRead;
        long totalBytes = 0;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }
        
        // Verify the downloaded file size is reasonable
        // Noto Sans font should be at least 100KB
        if (totalBytes < 100000) {
            throw new IOException("Downloaded font file is too small: " + totalBytes + " bytes");
        }
    }
}

     /**
     * Translate a document from Campus.
     */
    public void translateCampusDocument(String inputFilePath, 
                                        String outputFileName, String targetLanguage) throws IOException, SQLException {
       // logger.logInfo("Processing Campus document translation for ID: {}", translationDocumentID);
        System.out.println("!!Processing Campus document translation for ID: {}");
        try (PDDocument inputDocument = PDDocument.load(new File(inputFilePath))) {
            processDocument(inputDocument, targetLanguage);
            
            // Save to file
            File outFile = new File(outputFileName);
            if (!outFile.isAbsolute()) {
                outFile = new File(outputFileLocation, outputFileName);
            }
            
            try (OutputStream outputStream = new FileOutputStream(outFile)) {
                FileUtilityHelper.deleteFileIfExists(outputFileName);
                translatedDocument.save(outputStream);
            }
            
          //  markRequestCompleted(translationDocumentID, FileUtilityHelper.getFileName(outputFileName));
          //  logger.logInfo("Successfully completed translation for ID: {}", translationDocumentID);
        } catch (IOException e) {
           logger.logError("Error during translation process: " + e.getMessage());
          System.out.println("Error during translation process: " + e.getMessage());
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
        
        try (PDDocument inputDocument = PDDocument.load(new File(inputFilePath))) {
            processDocument(inputDocument, targetLanguage);
            
            // Get translated document as bytes
            byte[] translatedData;
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                translatedDocument.save(outputStream);
                translatedData = outputStream.toByteArray();
            }
            
            // Process database operations
            processBackpackDatabase(translationDocumentID, documentID, translatedData, targetLanguage, fullLanguage);
            
            logger.logInfo("Successfully completed translation for ID: {}", translationDocumentID);
        } catch (IOException e) {
            logger.logError("Error during document translation for ID: {}", translationDocumentID, e);
            throw new IOException("Error during translation process", e);
        }
    }
/**
 * Processes a document for translation, handling font initialization and caching.
 * @param inputDocument The document to process
 * @param targetLanguage The target language for translation
 * @throws IOException if document processing fails
 */
    private void processDocument(PDDocument inputDocument, String targetLanguage) throws IOException {
        // Try to get font from cache first
        String fontCacheKey = "default_font";
        PDFont cachedFont = FontCache.get(fontCacheKey);
        if (cachedFont != null) {
            // Use cached font if available
            unicodeFont = cachedFont;
            isUnicodeFontAvailable = true;
        } else {
            // Initialize new font if not in cache
            initializeFont();
            if (isUnicodeFontAvailable) {
                FontCache.put(fontCacheKey, unicodeFont);
            }
        }
        PDFRenderer pdfRenderer = new PDFRenderer(inputDocument);
        
        for (int page = 0; page < inputDocument.getNumberOfPages(); ++page) {
            int pageNumber = page + 1;
            logger.logDebug("Processing page: {}", pageNumber);
            
            BufferedImage image = pdfRenderer.renderImage(page, 1.0f, ImageType.RGB);
            
            ByteBuffer imageBytes;
            try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
                ImageIOUtil.writeImage(image, "jpeg", byteArrayOutputStream);
                imageBytes = ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
            }
            
            List<TextLine> lines = extractTextAndTranslate(imageBytes, targetLanguage);
            addPageWithFormatting(image, lines);
            
            logger.logDebug("Page {} processed successfully", pageNumber);
        }
    }

    /**
     * Extract text from image and translate it.
     */
    private List<TextLine> extractTextAndTranslate(ByteBuffer imageBytes, String targetLanguage) {
        List<TextLine> lines = new ArrayList<>();
        
        // Create document from image bytes
        Document document = Document.builder()
                .bytes(SdkBytes.fromByteBuffer(imageBytes))
                .build();
                
        // Detect text in document
        DetectDocumentTextRequest request = DetectDocumentTextRequest.builder()
                .document(document)
                .build();
                
        DetectDocumentTextResponse response = awsTextractClient.detectDocumentText(request);
        
        // Process each text block
        for (Block block : response.blocks()) {
            if (block.blockType() == BlockType.LINE) {
                String sourceText = block.text();
                
                // Translate the text
                TranslateTextRequest translateRequest = TranslateTextRequest.builder()
                        .sourceLanguageCode(DEFAULT_SOURCE_LANGUAGE)
                        .targetLanguageCode(targetLanguage)
                        .text(sourceText)
                        .build();
                        
                TranslateTextResponse translateResponse = awsTranslateClient.translateText(translateRequest);
                
                // Get bounding box information
                BoundingBox boundingBox = block.geometry().boundingBox();
                
                // Add to lines list
                lines.add(new TextLine(
                    boundingBox.left(),
                    boundingBox.top(),
                    boundingBox.width(),
                    boundingBox.height(),
                    sourceText,
                    translateResponse.translatedText()
                ));
            }
        }
        
        return lines;
    }
        /**
     * Add a page to the translated document with formatting.
     */
    private void addPageWithFormatting(BufferedImage image, List<TextLine> lines) throws IOException {
        float width = image.getWidth();
        float height = image.getHeight();
        
        // Create new page
        PDRectangle box = new PDRectangle(width, height);
        PDPage page = new PDPage(box);
        translatedDocument.addPage(page);
        
        // Add image to page
        PDImageXObject pdImage = JPEGFactory.createFromImage(translatedDocument, image);
        
        try (PDPageContentStream contentStream = new PDPageContentStream(
                translatedDocument, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
            
            // Draw background image
            contentStream.drawImage(pdImage, 0, 0);
            contentStream.setRenderingMode(RenderingMode.FILL);
            
            // Process each text line
            for (TextLine line : lines) {
                String textToDisplay = line.translatedText;
                
                // Calculate absolute positions
                float absX = (float) (line.left * width);
                float absY = (float) (height - line.top * height - line.height * height);
                float boxWidth = (float) (line.width * width);
                float boxHeight = (float) (line.height * height);
                
                // Choose font
                PDFont fontToUse = isUnicodeFontAvailable ? unicodeFont : PDType1Font.HELVETICA;
                
                // Calculate font size
                FontInfo fontInfo = calculateFontSize(textToDisplay, boxWidth, boxHeight, fontToUse);
                
                // Draw white background for text
                contentStream.setNonStrokingColor(Color.WHITE);
                contentStream.addRect(absX, absY - 2, boxWidth + 2, boxHeight + 2);
                contentStream.fill();
                
                // Draw text
                contentStream.setNonStrokingColor(Color.BLACK);
                contentStream.beginText();
                
                try {
                    // Test if font can encode the text
                    fontToUse.encode(textToDisplay);
                    
                    contentStream.setFont(fontToUse, fontInfo.fontSize);
                    contentStream.newLineAtOffset(absX, absY);
                    
                    if (isUnicodeFontAvailable) {
                        contentStream.showText(textToDisplay);
                    } else {
                        String substitutedText = substituteCharacters(textToDisplay);
                        contentStream.showText(substitutedText);
                    }
                } catch (IllegalArgumentException e) {
                    logger.logError("Font encoding error for text: {}", textToDisplay);
                    
                    // Fallback to substituted text
                    String substitutedText = substituteCharacters(textToDisplay);
                    contentStream.setFont(PDType1Font.HELVETICA, fontInfo.fontSize);
                    contentStream.newLineAtOffset(absX, absY);
                    contentStream.showText(substitutedText);
                }
                
                contentStream.endText();
            }
        }
    }
    /**
 * Cache system for fonts to improve performance when processing multiple documents.
 * Uses a concurrent map to ensure thread safety.
 */
private static class FontCache {
    // Thread-safe map to store fonts
    private static final Map<String, PDFont> cache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 5;

    /**
     * Retrieves a font from the cache.
     * @param key The cache key for the font
     * @return The cached font or null if not found
     */
    public static PDFont get(String key) {
        return cache.get(key);
    }

    /**
     * Stores a font in the cache, managing cache size.
     * @param key The cache key for the font
     * @param font The font to cache
     */
    public static void put(String key, PDFont font) {
        // Implement cache size management
        if (cache.size() >= MAX_CACHE_SIZE) {
            // Remove oldest entry if cache is full
            String oldestKey = cache.keySet().iterator().next();
            cache.remove(oldestKey);
        }
        cache.put(key, font);
    }

    /**
     * Clears all cached fonts.
     */
    public static void clear() {
        cache.clear();
    }
}
        /**
     * Calculate appropriate font size for text.
     */
    private FontInfo calculateFontSize(String text, float boxWidth, float boxHeight, PDFont font) throws IOException {
        int fontSize = 20;
        float textWidth = font.getStringWidth(text) / 1000 * fontSize;
        float textHeight = font.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * fontSize;

        if (textWidth > boxWidth) {
            while (textWidth > boxWidth && fontSize > 6) {
                fontSize -= 1;
                textWidth = font.getStringWidth(text) / 1000 * fontSize;
                textHeight = font.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * fontSize;
            }
        } else if (textWidth < boxWidth) {
            while (textWidth < boxWidth && fontSize < 36) {
                fontSize += 1;
                textWidth = font.getStringWidth(text) / 1000 * fontSize;
                textHeight = font.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * fontSize;
                
                if (textWidth > boxWidth) {
                    fontSize -= 1;
                    break;
                }
            }
        }

        FontInfo fontInfo = new FontInfo();
        fontInfo.fontSize = fontSize;
        fontInfo.textHeight = textHeight;
        fontInfo.textWidth = textWidth;
        return fontInfo;
    }

    /**
     * Substitute characters that can't be displayed with the current font.
     */
    private String substituteCharacters(String text) {
        Map<String, String> charMap = new HashMap<>();
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) {
                // Replace non-ASCII characters with closest ASCII equivalent or '?'
                result.append(charMap.getOrDefault(String.valueOf(c), "?"));
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }

/**
 * Provides fallback fonts when the primary Unicode font fails to load.
 * Attempts multiple standard fonts in order of preference.
 */
private void fallbackToBasicFont() {
    try {
        // Array of fallback fonts in order of preference
        PDFont[] fallbackFonts = {
            PDType1Font.HELVETICA,      // Most common, good general support
            PDType1Font.TIMES_ROMAN,    // Good readability
            PDType1Font.COURIER         // Last resort, fixed-width
        };

        for (PDFont font : fallbackFonts) {
            try {
                unicodeFont = font;
                if (verifyFont(font)) {
                    isUnicodeFontAvailable = false;
                    logger.logInfo("Using {} as fallback font. Basic character support enabled.", 
                                 font.getName());
                    return;
                }
            } catch (Exception e) {
                logger.logDebug("Skipping fallback font {}: {}", font.getName(), e.getMessage());
            }
        }

        // Last resort
        unicodeFont = PDType1Font.HELVETICA;
        isUnicodeFontAvailable = false;
        logger.logInfo("Using Helvetica as final fallback. Basic ASCII character support only.");

    } catch (Exception e) {
        logger.logError("Critical error in fallback font initialization", e);
        throw new RuntimeException("No usable font available", e);
    }
}
        /**
     * Download a file with retry mechanism.
     */
    private void downloadWithRetry(URL url, File destination) throws IOException {
        int maxRetries = 3;
        int retryDelayMs = 1000; // 1 second

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    return; // Success
                }
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw e; // Rethrow on last attempt
                }
                logger.logWarn("Download attempt {} failed, retrying in {}ms", attempt, retryDelayMs);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted", ie);
                }
                retryDelayMs *= 2; // Exponential backoff
            }
        }
    }

    /**
     * Mark a translation request as completed.
     */
    private void markRequestCompleted(int translationID, String completedName) throws SQLException {
        if (campusConnection == null || campusConnection.isClosed()) {
            logger.logError("Database connection is not available or already closed.");
            throw new SQLException("Connection is closed or unavailable.");
        }
        
        String sql = "UPDATE [ccs_dev].CCS_TranslationDocument " +
                    "SET [completed] = 1, [completedDate] = GETDATE(), completedName = ? " +
                    "WHERE translationDocumentID = ?";

        try (PreparedStatement pstmt = campusConnection.prepareStatement(sql)) {
            pstmt.setString(1, completedName);
            pstmt.setInt(2, translationID);
            int rowsUpdated = pstmt.executeUpdate();

            if (rowsUpdated > 0) {
                logger.logInfo("Successfully marked translation ID {} as completed.", translationID);
            } else {
                logger.logWarn("No rows updated. Translation ID {} not found.", translationID);
            }
        } catch (SQLException e) {
            logger.logError("Error marking translation ID {} as completed: {}", translationID, e.getMessage(), e);
            throw new SQLException("Failed to update translation status.", e);
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
        markRequestCompleted(translationDocumentID, scheduleName + "_" + targetLanguage + ".pdf");
    }

    /**
     * Close resources when done.
     */
    @Override
    public void close() {
        logger.logDebug("Closing AWS document translation service resources");
        
        if (awsTextractClient != null) {
          // not available until later version  awsTextractClient.close();
          com.infinitecampus.ccs.lingo.utility.AwsClientUtils.safeClose(awsTextractClient, logger);
          //AwsClientUtils.safeClose(awsTranslateClient, logger);
            logger.logDebug("AWS Textract client closed successfully");
        }
        
        if (awsTranslateClient != null) {
             // not available until later version   awsTranslateClient.close();
                com.infinitecampus.ccs.lingo.utility.AwsClientUtils.safeClose(awsTranslateClient, logger);
            logger.logDebug("AWS Translate client closed successfully");
        }
        
        if (translatedDocument != null) {
            try {
                translatedDocument.close();
                logger.logDebug("PDF document closed successfully");
            } catch (IOException e) {
                logger.logError("Error closing PDF document", e);
            }
        }
    }
}