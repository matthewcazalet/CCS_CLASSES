package com.infinitecampus.ccs.lingo.utility;

public class PdfResponse {
    private String filename;
    private String content;

    public PdfResponse(String filename, String content) {
        this.filename = filename;
        this.content = content;
    }

    public String getFilename() {
        return filename;
    }

    public String getContent() {
        return content;
    }
}
