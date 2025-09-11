package com.infinitecampus.ccs.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject; 
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class JSONResponseHelper {


    public JsonArray EncryptData(ResultSet rs,String[]encryptColumns)throws SQLException {
        final String sCurrentDate = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(java.time.LocalDate.now());

        ResultSetMetaData rsmd = rs.getMetaData();
        JsonArray jsonArray = new JsonArray();
        // Convert the array to a Set for faster lookups if it's not null
        Set<String> encryptColumnSet = (encryptColumns != null && encryptColumns.length > 0)
        ? new HashSet<>(Arrays.asList(encryptColumns))
        : null;

        while (rs.next()) {
            JsonObject obj = new JsonObject();
            int numColumns = rsmd.getColumnCount();
            for (int i = 1; i <= numColumns; i++) {
                String columnName = rsmd.getColumnName(i).toLowerCase();
                Object columnValue = rs.getObject(i); 

                // Encrypt specified columns if needed
                if (encryptColumnSet != null && encryptColumnSet.contains(columnName)) {
                    if (columnValue != null) {
                        try{
                            columnValue = com.infinitecampus.utility.Blowfish.encrypt(String.valueOf(columnValue), sCurrentDate);
                        }
                        catch(Exception e){
                            columnValue="ENCRYPTION_ERROR";
                        }
                    }
                }
                if (columnValue == null) {
                    obj.add(columnName, JsonNull.INSTANCE);
                } else {
                    obj.addProperty(columnName, columnValue.toString());
                }

            }
            jsonArray.add(obj); // Add JsonObject to JsonArray
        }

        return jsonArray;
    }
}