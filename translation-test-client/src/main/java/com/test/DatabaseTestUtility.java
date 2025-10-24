package com.test;

import java.sql.*;
import java.util.Properties;
import java.io.FileInputStream;
import java.io.IOException;
import com.microsoft.sqlserver.jdbc.SQLServerException;

public class DatabaseTestUtility {
    
    private static Properties config = new Properties();
    
    public static void main(String[] args) {
        System.out.println("Database Test Utility Started");
        
        try {
            // Load configuration
            loadConfiguration();
            
            // Get command line arguments
            String operation = args.length > 0 ? args[0] : "check";
            String param = args.length > 1 ? args[1] : null;
            
            System.out.println("Database URL: " + config.getProperty("db.url").replaceAll("password=.*", "password=***"));
            
            // Run based on operation
            switch (operation.toLowerCase()) {
                case "check":
                    checkTranslationData();
                    break;
                case "checkconfig":
                    checkConfigurationTables();
                    break;
                case "pending":
                    checkPendingTranslations();
                    break;
                case "token":
                    if (param != null) {
                        debugTokenData(param);
                    } else {
                        System.out.println("Error: Token parameter required");
                    }
                    break;
                case "create":
                    createTestData();
                    break;
                case "createwithconfig":
                    createTestDataWithConfig();
                    break;
                case "clean":
                    cleanTestData();
                    break;
                case "tables":
                    listAllTables();
                    break;
                case "schema":
                    showTableSchema(param != null ? param : "CCS_TranslationText");
                    break;
                case "query":
                    if (param != null) {
                        executeCustomQuery(param);
                    } else {
                        System.out.println("Error: Query parameter required");
                    }
                    break;
                default:
                    System.out.println("Unknown operation: " + operation);
                    printUsage();
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void loadConfiguration() throws IOException {
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            config.load(fis);
            System.out.println("Configuration loaded successfully\n");
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
    
    // Check translation data
    private static void checkTranslationData() throws Exception {
        System.out.println("=== Checking Translation Data ===\n");
        
        try (Connection conn = getConnection()) {
            // Today's records
            String todayQuery = "SELECT translationTextID, translationConfigID, createDate, " +
                              "token, completed, completedDate, " +
                              "CASE WHEN translationData IS NULL THEN 'NULL' " +
                              "     WHEN LEN(translationData) > 100 THEN LEFT(translationData, 100) + '...' " +
                              "     ELSE translationData END as dataPreview " +
                              "FROM ccs_lng.CCS_TranslationText " +
                              "WHERE CAST(createDate AS DATE) = CAST(GETDATE() AS DATE) " +
                              "ORDER BY translationTextID DESC";
            
            System.out.println("Today's translation records:");
            printResultSet(conn, todayQuery);
            
            // Summary statistics
            String statsQuery = "SELECT " +
                              "COUNT(*) as TotalRecords, " +
                              "SUM(CASE WHEN completed = 0 THEN 1 ELSE 0 END) as Pending, " +
                              "SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as Completed, " +
                              "SUM(CASE WHEN translationConfigID IS NULL THEN 1 ELSE 0 END) as NoConfig " +
                              "FROM ccs_lng.CCS_TranslationText";
            
            System.out.println("\nSummary Statistics:");
            printResultSet(conn, statsQuery);
            
            // Recent tokens
            String tokenQuery = "SELECT DISTINCT TOP 10 token, COUNT(*) as RecordCount, " +
                              "MAX(createDate) as LastCreated " +
                              "FROM ccs_lng.CCS_TranslationText " +
                              "GROUP BY token " +
                              "ORDER BY LastCreated DESC";
            
            System.out.println("\nRecent Tokens:");
            printResultSet(conn, tokenQuery);
        }
    }
    
    // Check configuration tables
    private static void checkConfigurationTables() throws Exception {
        System.out.println("=== Checking Configuration Tables ===\n");
        
        try (Connection conn = getConnection()) {
            // Try to find config table
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet tables = meta.getTables(null, "ccs_lng", "%Config%", new String[]{"TABLE"});
            
            System.out.println("Configuration-related tables:");
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                System.out.println("  - " + tableName);
                
                // Show sample data from each config table
                String query = "SELECT TOP 5 * FROM ccs_lng." + tableName;
                try {
                    System.out.println("\n  Sample data from " + tableName + ":");
                    printResultSet(conn, query);
                } catch (SQLException e) {
                    System.out.println("  Error reading table: " + e.getMessage());
                }
            }
            
            // Check foreign key relationships
            System.out.println("\n\nChecking Foreign Key Relationships:");
            ResultSet fks = meta.getImportedKeys(null, "ccs_lng", "CCS_TranslationText");
            while (fks.next()) {
                System.out.println("  FK: " + fks.getString("FK_NAME") + 
                                 " references " + fks.getString("PKTABLE_NAME") + 
                                 "." + fks.getString("PKCOLUMN_NAME"));
            }
        }
    }
    
    // Check pending translations
    private static void checkPendingTranslations() throws Exception {
        System.out.println("=== Checking Pending Translations ===\n");
        
        try (Connection conn = getConnection()) {
            // Pending by date
            String pendingQuery = "SELECT CAST(createDate AS DATE) as Date, " +
                                "COUNT(*) as PendingCount, " +
                                "COUNT(DISTINCT token) as UniqueTokens, " +
                                "COUNT(DISTINCT translationConfigID) as ConfigsUsed " +
                                "FROM ccs_lng.CCS_TranslationText " +
                                "WHERE completed = 0 " +
                                "GROUP BY CAST(createDate AS DATE) " +
                                "ORDER BY Date DESC";
            
            System.out.println("Pending translations by date:");
            printResultSet(conn, pendingQuery);
            
            // Detailed pending for today
            String todayPending = "SELECT * FROM ccs_lng.CCS_TranslationText " +
                                "WHERE completed = 0 " +
                                "AND CAST(createDate AS DATE) = CAST(GETDATE() AS DATE)";
            
            System.out.println("\n\nToday's pending translations (detailed):");
            printResultSet(conn, todayPending);
        }
    }
    
    // Debug specific token
    private static void debugTokenData(String token) throws Exception {
        System.out.println("=== Debugging Token: " + token + " ===\n");
        
        try (Connection conn = getConnection()) {
            // Records with this token
            String tokenRecords = "SELECT * FROM ccs_lng.CCS_TranslationText WHERE token = ?";
            PreparedStatement ps = conn.prepareStatement(tokenRecords);
            ps.setString(1, token);
            
            System.out.println("Records with this token:");
            printResultSet(ps.executeQuery());
            
            // Check if configuration exists
            String configCheck = "SELECT tt.translationTextID, tt.translationConfigID, " +
                              "tc.* FROM ccs_lng.CCS_TranslationText tt " +
                              "LEFT JOIN ccs_lng.CCS_TranslationConfig tc " +
                              "ON tt.translationConfigID = tc.translationConfigID " +
                              "WHERE tt.token = ?";
            
            ps = conn.prepareStatement(configCheck);
            ps.setString(1, token);
            
            System.out.println("\n\nConfiguration join results:");
            try {
                printResultSet(ps.executeQuery());
            } catch (SQLException e) {
                System.out.println("Error joining with config table: " + e.getMessage());
            }
        }
    }
    
    // Create test data
    private static void createTestData() throws Exception {
        System.out.println("=== Creating Test Data ===\n");
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            String insert = "INSERT INTO ccs_lng.CCS_TranslationText " +
                          "(translationData, completed) VALUES (?, 0)";
            
            PreparedStatement ps = conn.prepareStatement(insert);
            
            // Create several test records
            String[] testData = {
                "{\"text\": \"Hello World\", \"source\": \"en\", \"target\": \"es\"}",
                "{\"text\": \"Good morning\", \"source\": \"en\", \"target\": \"fr\"}",
                "{\"text\": \"Thank you\", \"source\": \"en\", \"target\": \"de\"}",
                "{\"text\": \"Welcome\", \"source\": \"en\", \"target\": \"ja\"}"
            };
            
            for (String data : testData) {
                ps.setString(1, data);
                ps.addBatch();
            }
            
            int[] results = ps.executeBatch();
            conn.commit();
            
            System.out.println("Created " + results.length + " test records");
            
            // Show what was created
            String showNew = "SELECT TOP " + results.length + 
                           " translationTextID, token, translationData " +
                           "FROM ccs_lng.CCS_TranslationText " +
                           "ORDER BY translationTextID DESC";
            
            System.out.println("\nNewly created records:");
            printResultSet(conn, showNew);
        }
    }
    
    // Create test data with configuration
    private static void createTestDataWithConfig() throws Exception {
        System.out.println("=== Creating Test Data with Configuration ===\n");
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            
            // First, check for existing config
            String configQuery = "SELECT TOP 1 translationConfigID FROM ccs_lng.CCS_TranslationConfig";
            Integer configId = null;
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(configQuery)) {
                if (rs.next()) {
                    configId = rs.getInt("translationConfigID");
                    System.out.println("Using existing config ID: " + configId);
                }
            } catch (SQLException e) {
                System.out.println("No configuration table found or accessible");
            }
            
            // Create test data
            String insert = "INSERT INTO ccs_lng.CCS_TranslationText " +
                          "(translationConfigID, translationData, completed) VALUES (?, ?, 0)";
            
            PreparedStatement ps = conn.prepareStatement(insert);
            
            if (configId != null) {
                ps.setInt(1, configId);
            } else {
                ps.setNull(1, Types.INTEGER);
            }
            
            ps.setString(2, "{\"text\": \"Test with config\", \"source\": \"en\", \"target\": \"es\"}");
            
            int rows = ps.executeUpdate();
            conn.commit();
            
            System.out.println("Created " + rows + " record(s) with config ID: " + configId);
        }
    }
    
    // Clean test data
    private static void cleanTestData() throws Exception {
        System.out.println("=== Cleaning Test Data ===\n");
        
        try (Connection conn = getConnection()) {
            // Only delete today's uncompleted test records
            String delete = "DELETE FROM ccs_lng.CCS_TranslationText " +
                          "WHERE completed = 0 " +
                          "AND CAST(createDate AS DATE) = CAST(GETDATE() AS DATE) " +
                          "AND translationData LIKE '%Test%'";
            
            Statement stmt = conn.createStatement();
            int deleted = stmt.executeUpdate(delete);
            
            System.out.println("Deleted " + deleted + " test record(s)");
        }
    }
    
    // List all tables in schema
    private static void listAllTables() throws Exception {
        System.out.println("=== All Tables in ccs_lng Schema ===\n");
        
        try (Connection conn = getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet tables = meta.getTables(null, "ccs_lng", "%", new String[]{"TABLE"});
            
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                
                // Get row count
                String countQuery = "SELECT COUNT(*) as cnt FROM ccs_lng." + tableName;
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(countQuery)) {
                    rs.next();
                    System.out.println(String.format("  %-40s %d rows", tableName, rs.getInt("cnt")));
                } catch (SQLException e) {
                    System.out.println(String.format("  %-40s (error counting rows)", tableName));
                }
            }
        }
    }
        // Show table schema
        private static void showTableSchema(String tableName) throws Exception {
            System.out.println("=== Schema for table: " + tableName + " ===\n");
            
            try (Connection conn = getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                
                // Get columns
                ResultSet columns = meta.getColumns(null, "ccs_lng", tableName, null);
                
                System.out.println("Columns:");
                System.out.println(String.format("%-30s %-20s %-10s %-10s", "Column Name", "Type", "Size", "Nullable"));
                System.out.println("-".repeat(75));
                
                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    String typeName = columns.getString("TYPE_NAME");
                    int columnSize = columns.getInt("COLUMN_SIZE");
                    String nullable = columns.getString("IS_NULLABLE");
                    
                    System.out.println(String.format("%-30s %-20s %-10d %-10s", 
                        columnName, typeName, columnSize, nullable));
                }
                
                // Get primary keys
                System.out.println("\nPrimary Keys:");
                ResultSet pks = meta.getPrimaryKeys(null, "ccs_lng", tableName);
                while (pks.next()) {
                    System.out.println("  " + pks.getString("COLUMN_NAME"));
                }
                
                // Get foreign keys
                System.out.println("\nForeign Keys:");
                ResultSet fks = meta.getImportedKeys(null, "ccs_lng", tableName);
                while (fks.next()) {
                    System.out.println("  " + fks.getString("FKCOLUMN_NAME") + 
                                     " -> " + fks.getString("PKTABLE_NAME") + 
                                     "." + fks.getString("PKCOLUMN_NAME"));
                }
                
                // Get indexes
                System.out.println("\nIndexes:");
                ResultSet indexes = meta.getIndexInfo(null, "ccs_lng", tableName, false, false);
                while (indexes.next()) {
                    String indexName = indexes.getString("INDEX_NAME");
                    if (indexName != null) {
                        System.out.println("  " + indexName + " on " + 
                                         indexes.getString("COLUMN_NAME"));
                    }
                }
            }
        }
        
        // Execute custom query
        private static void executeCustomQuery(String query) throws Exception {
            System.out.println("=== Executing Custom Query ===");
            System.out.println("Query: " + query + "\n");
            
            try (Connection conn = getConnection()) {
                printResultSet(conn, query);
            }
        }
        
        // Helper method to print result set
        private static void printResultSet(Connection conn, String query) throws SQLException {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                printResultSet(rs);
            }
        }
        
        // Helper method to print result set
        private static void printResultSet(ResultSet rs) throws SQLException {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            
            // Print column headers
            StringBuilder header = new StringBuilder();
            StringBuilder separator = new StringBuilder();
            
            for (int i = 1; i <= columnCount; i++) {
                String columnName = meta.getColumnName(i);
                header.append(String.format("%-20s ", columnName));
                separator.append("-".repeat(20)).append(" ");
            }
            
            System.out.println(header);
            System.out.println(separator);
            
            // Print rows
            int rowCount = 0;
            while (rs.next() && rowCount < 100) { // Limit to 100 rows
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    Object value = rs.getObject(i);
                    String displayValue = value == null ? "NULL" : value.toString();
                    
                    // Truncate long values
                    if (displayValue.length() > 20) {
                        displayValue = displayValue.substring(0, 17) + "...";
                    }
                    
                    row.append(String.format("%-20s ", displayValue));
                }
                System.out.println(row);
                rowCount++;
            }
            
            if (rowCount == 0) {
                System.out.println("  (No records found)");
            } else if (rs.next()) {
                System.out.println("  ... (more rows exist)");
            }
            
            System.out.println();
        }
        
        private static void printUsage() {
            System.out.println("\nUsage: java -jar database-test-utility.jar [operation] [parameter]");
            System.out.println("\nOperations:");
            System.out.println("  check                 - Check translation data overview");
            System.out.println("  checkconfig          - Check configuration tables");
            System.out.println("  pending              - Show pending translations");
            System.out.println("  token [token]        - Debug specific token");
            System.out.println("  create               - Create test translation records");
            System.out.println("  createwithconfig     - Create test records with config");
            System.out.println("  clean                - Clean test data");
            System.out.println("  tables               - List all tables in schema");
            System.out.println("  schema [table]       - Show table schema (default: CCS_TranslationText)");
            System.out.println("  query [sql]          - Execute custom SQL query");
            System.out.println("\nExamples:");
            System.out.println("  java -jar database-test-utility.jar check");
            System.out.println("  java -jar database-test-utility.jar token E9EC28D7-0D4F-4D69-9E30-3D2EB31B521F");
            System.out.println("  java -jar database-test-utility.jar schema CCS_TranslationConfig");
            System.out.println("  java -jar database-test-utility.jar query \"SELECT TOP 10 * FROM ccs_lng.CCS_TranslationText\"");
        }
    }