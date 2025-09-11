package com.infinitecampus.ccs.lingo.utility;


import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.infinitecampus.utility.Blowfish;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JsonResponseHelper {

    // Overloaded method to handle success, message, and PDF data
    public static String GetJsonResponse(boolean success, String message, List<PdfResponse> pdfs) {
        Gson gson = new Gson();
        JsonResponse jsonResponse = new JsonResponse(success, message, pdfs);
        return gson.toJson(jsonResponse);
    }

    public static String GetJsonResponse(boolean success, String message) {
        Gson gson = new Gson();
        JsonResponse jsonResponse = new JsonResponse(success, message);
        return gson.toJson(jsonResponse);
    }
     public static JsonArray EncryptData(ResultSet rs, String[] encryptColumns) throws SQLException {
        String currentDate = DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now());
        ResultSetMetaData rsmd = rs.getMetaData();
        JsonArray jsonArray = new JsonArray();
        Set<String> encryptColumnSet = null;

        if (encryptColumns != null && encryptColumns.length > 0) {
            encryptColumnSet = new HashSet<>(Arrays.asList(encryptColumns));
        }

        while (rs.next()) {
            JsonObject obj = new JsonObject();
            int numColumns = rsmd.getColumnCount();

            for (int i = 1; i <= numColumns; ++i) {
                String columnName = rsmd.getColumnName(i).toLowerCase();
                Object columnValue = rs.getObject(i);

                if (encryptColumnSet != null && encryptColumnSet.contains(columnName) && columnValue != null) {
                    columnValue = Blowfish.encrypt(String.valueOf(columnValue), currentDate);
                }

                obj.addProperty(columnName, String.valueOf(columnValue));
            }

            jsonArray.add(obj);
        }

        return jsonArray;
    }
}

// JSON response class with an optional list of PDFs
class JsonResponse {
    private boolean success;
    private String message;
    private List<PdfResponse> pdfs;  

    public JsonResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public JsonResponse(boolean success, String message, List<PdfResponse> pdfs) {
        this.success = success;
        this.message = message;
        this.pdfs = pdfs;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public List<PdfResponse> getPdfs() {
        return pdfs;
    }
}

