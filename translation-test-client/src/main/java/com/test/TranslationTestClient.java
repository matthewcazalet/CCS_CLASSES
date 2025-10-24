package com.test;

import com.infinitecampus.ccs.lingo.prism.TranslateTextRequest;
import com.infinitecampus.CampusObject;
import com.infinitecampus.prism.Prism;
import com.infinitecampus.system.CampusApp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;

public class TranslationTestClient {
    
    private static Properties config = new Properties();
    
    public static void main(String[] args) {
        System.out.println("Translation Test Client Started");
        
        try {
            // Load configuration
            loadConfiguration();
            
            // Get command line arguments
            String mode = args.length > 0 ? args[0] : "token";
            
            // Enhanced token handling to support null
            String token = null;
            if (args.length > 1) {
                token = args[1];
                if ("null".equalsIgnoreCase(token) || "NULL".equals(token)) {
                    token = null;
                }
            } else {
                token = getConfigToken("test.default.token", "test-token-123");
            }
            
            System.out.println("Using configuration:");
            System.out.println("  Default Token: " + (token == null ? "null" : token));
            System.out.println("  Database URL: " + config.getProperty("db.url").replaceAll("password=.*", "password=***"));
            
            // Run based on mode
            switch (mode.toLowerCase()) {
                case "token":
                    testWithToken(token);
                    break;
                case "timestamp":
                    testWithTimestamp();
                    break;
                case "direct":
                    testDirect(token);
                    break;
                case "directwithtoken":
                    testDirectWithToken(token);
                    break;
                case "mock":
                    testWithMockPrism(token);
                    break;
                default:
                    System.out.println("Unknown mode: " + mode);
                    printUsage();
            }
            
        } catch (Exception e) {
            System.err.println("Error in test client: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Helper method to get token from config with null support
    private static String getConfigToken(String key, String defaultValue) {
        String value = config.getProperty(key);
        
        if (value == null) {
            return defaultValue;
        }
        
        // Trim whitespace
        value = value.trim();
        
        // Check for explicit null values
        if (value.isEmpty() || 
            value.equalsIgnoreCase("null") || 
            value.equalsIgnoreCase("NULL") ||
            value.equals("~") ||
            value.equalsIgnoreCase("none")) {
            return null;
        }
        
        return value;
    }
    
    private static void loadConfiguration() throws IOException {
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            config.load(fis);
            System.out.println("Configuration loaded successfully");
        }
    }
    
    private static Connection getConnection() throws Exception {
        String url = config.getProperty("db.url");
        String username = config.getProperty("db.username");
        String password = config.getProperty("db.password");
        String driver = config.getProperty("db.driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        
        Class.forName(driver);
        return DriverManager.getConnection(url, username, password);
    }
    
    // Test 1: Direct approach without token (timestamp version)
    private static void testDirect(String token) throws Exception {
        System.out.println("\n=== Direct Test (Timestamp Version) ===");
        System.out.println("Token parameter (ignored): " + (token == null ? "null" : token));
        
        Connection conn = null;
        TranslateTextRequest request = null;
        
        try {
            conn = getConnection();
            System.out.println("✓ Database connection established");
            
            // Create using default constructor
            request = new TranslateTextRequest();
            System.out.println("✓ Created TranslateTextRequest instance");
            
            // Use reflection to set the connection directly
            Field conField = CampusObject.class.getDeclaredField("con");
            conField.setAccessible(true);
            conField.set(request, conn);
            System.out.println("✓ Connection injected via reflection");
            
            // Call the procedure WITHOUT token (timestamp version)
            System.out.println("Calling translatetextProcedure() - timestamp version...");
            request.translatetextProcedure();
            
            System.out.println("\n✓ Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        } finally {
            handleConnectionClose(conn, request);
        }
    }
    
    // New test: Direct approach WITH token
    private static void testDirectWithToken(String token) throws Exception {
        System.out.println("\n=== Direct Test (Token Version) ===");
        System.out.println("Token: " + (token == null ? "null" : "\"" + token + "\""));
        
        Connection conn = null;
        TranslateTextRequest request = null;
        
        try {
            conn = getConnection();
            System.out.println("✓ Database connection established");
            
            // Create using default constructor
            request = new TranslateTextRequest();
            System.out.println("✓ Created TranslateTextRequest instance");
            
            // Use reflection to set the connection directly
            Field conField = CampusObject.class.getDeclaredField("con");
            conField.setAccessible(true);
            conField.set(request, conn);
            System.out.println("✓ Connection injected via reflection");
            
            // Call the procedure WITH token
            System.out.println("Calling translatetextProcedure(" + (token == null ? "null" : "\"" + token + "\"") + ")...");
            request.translatetextProcedure(token);
            
            System.out.println("\n✓ Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        } finally {
            handleConnectionClose(conn, request);
        }
    }
    
    // Helper method to handle connection closing
    private static void handleConnectionClose(Connection conn, TranslateTextRequest request) {
        try {
            if (conn != null && !conn.isClosed() && request != null) {
                try {
                    // Check if TranslateTextRequest owns the connection
                    Field ownsConnectionField = request.getClass().getDeclaredField("ownsConnection");
                    ownsConnectionField.setAccessible(true);
                    boolean ownsConnection = ownsConnectionField.getBoolean(request);
                    
                    if (!ownsConnection) {
                        conn.close();
                        System.out.println("✓ Connection closed by test client");
                    } else {
                        System.out.println("✓ Connection owned by TranslateTextRequest (not closing)");
                    }
                } catch (NoSuchFieldException e) {
                    // If field doesn't exist, close the connection to be safe
                    conn.close();
                    System.out.println("✓ Connection closed by test client (ownsConnection field not found)");
                }
            }
        } catch (Exception e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }
    
    // Test 2: Using mock Prism
    private static void testWithMockPrism(String token) throws Exception {
        System.out.println("\n=== Test with Mock Prism ===");
        System.out.println("Token: " + (token == null ? "null" : "\"" + token + "\""));
        
        Connection conn = null;
        try {
            // Initialize mock Prism
            initializeMockPrism();
            
            conn = getConnection();
            System.out.println("✓ Database connection established");
            
            // Create with app name
            TranslateTextRequest request = new TranslateTextRequest(conn, "TestApp", false);
            System.out.println("✓ Created TranslateTextRequest with app name");
            
            // Call the procedure
            System.out.println("Calling translatetextProcedure(" + (token == null ? "null" : "\"" + token + "\"") + ")...");
            request.translatetextProcedure(token);
            
            System.out.println("\n✓ Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        }
    }
    
    // Test 3: With token using CampusObject constructor
    private static void testWithToken(String token) throws Exception {
        System.out.println("\n=== Test with Token (CampusObject) ===");
        System.out.println("Token: " + (token == null ? "null" : "\"" + token + "\""));
        
        Connection conn = null;
        try {
            conn = getConnection();
            System.out.println("✓ Database connection established");
            
            // Create a base CampusObject
            CampusObject baseCO = new CampusObject(conn) {
                // Anonymous inner class
            };
            System.out.println("✓ Created base CampusObject");
            
            // Create TranslateTextRequest using CampusObject constructor
            TranslateTextRequest request = new TranslateTextRequest(baseCO);
            System.out.println("✓ Created TranslateTextRequest from CampusObject");
            
            // Call the procedure
            System.out.println("Calling translatetextProcedure(" + (token == null ? "null" : "\"" + token + "\"") + ")...");
            request.translatetextProcedure(token);
            
            System.out.println("\n✓ Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        }
    }
    
    // Test 4: With timestamp
    private static void testWithTimestamp() throws Exception {
        System.out.println("\n=== Test with Timestamp ===");
        
        Connection conn = null;
        try {
            conn = getConnection();
            System.out.println("✓ Database connection established");
            
            // Create using default constructor and inject connection
            TranslateTextRequest request = new TranslateTextRequest();
            
            Field conField = CampusObject.class.getDeclaredField("con");
            conField.setAccessible(true);
            conField.set(request, conn);
            System.out.println("✓ Connection injected");
            
            // Call the procedure without token (uses timestamp)
            System.out.println("Calling translatetextProcedure() with timestamp...");
            request.translatetextProcedure();
            
            System.out.println("\n✓ Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        }
    }
    
    private static void initializeMockPrism() {
        try {
            // Get or create the apps HashMap in Prism
            Field appsField = Prism.class.getDeclaredField("apps");
            appsField.setAccessible(true);
            
            java.util.HashMap<String, CampusApp> apps = 
                (java.util.HashMap<String, CampusApp>) appsField.get(null);
            
            if (apps == null) {
                apps = new java.util.HashMap<>();
                appsField.set(null, apps);
            }
            
            // Create a mock CampusApp
            CampusApp mockApp = new CampusApp();
            mockApp.state = "MN";
            mockApp.edition = "Test";
            
            // Add to Prism's registry
            apps.put("TestApp", mockApp);
            
            System.out.println("✓ Mock Prism initialized with TestApp");
            
        } catch (Exception e) {
            System.err.println("⚠ Could not initialize mock Prism: " + e.getMessage());
        }
    }
    
    private static void printUsage() {
        System.out.println("\nUsage: java -jar translation-test-client.jar [mode] [token]");
        System.out.println("\nModes:");
        System.out.println("  direct                - Direct test using timestamp (no token)");
        System.out.println("  directwithtoken [token] - Direct test with token (use 'null' for null)");
        System.out.println("  token [token]         - Test using CampusObject constructor");
        System.out.println("  timestamp             - Test with timestamp instead of token");
        System.out.println("  mock [token]          - Test with mock Prism framework");
        System.out.println("\nExamples:");
        System.out.println("  java -jar translation-test-client.jar direct");
        System.out.println("  java -jar translation-test-client.jar directwithtoken my-test-token");
        System.out.println("  java -jar translation-test-client.jar directwithtoken null");
    }
}