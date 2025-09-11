package com.infinitecampus.ccs.lingo.authenticate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Authenticate {  
    private static Connection conn;

    private static boolean isTokenAuthenticated(String token, String tableName) {
      String sql = "SELECT 1 FROM [ccs_dev].[" + tableName + "] WHERE token = TRY_CAST(? as uniqueidentifier)";
      try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
          pstmt.setString(1, token);
          try (ResultSet rs = pstmt.executeQuery()) {
              return rs.next();
          }
      } catch (SQLException e) {         
          e.printStackTrace();
          return false;
      }
  }
    public static boolean isGenerateOutputAuthenticated(Connection con, String token) {
      conn = con;
      return isTokenAuthenticated(token, "CCS_Request");
     }
  
     public static boolean isTranslatedTextAuthenticated(Connection con,String token){
      conn = con;
      return isTokenAuthenticated(token, "CCS_TranslationText");
     }
     public static boolean isTranslatedDocumentAuthenticated(Connection con, String token) {
      conn = con;
      return isTokenAuthenticated(token, "CCS_TranslationDocument");
     }
   }
