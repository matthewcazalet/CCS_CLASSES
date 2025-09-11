package com.infinitecampus.ccs.lingo.utility;

//import org.apache.pdfbox.pdmodel.PDDocument;
//import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;

public class PDFHelper {

    /**
     * Compares the text content of two PDF documents after normalizing whitespace
     * and ignoring case differences.
     *
     * @param pdf1 The first PDF document as a byte array.
     * @param pdf2 The second PDF document as a byte array.
     * @return true if the normalized text content is the same (case-insensitive), false otherwise.
     * @throws Exception if an error occurs while reading the PDFs.
     */
    public static boolean comparePdfText(byte[] pdf1, byte[] pdf2) throws Exception {
        //uses org.apache.pdfbox.pdmodel.PDDocument;
        /*/
        try (
            
         //   PDDocument doc1 = PDDocument.load(new ByteArrayInputStream(pdf1));
         //   PDDocument doc2 = PDDocument.load(new ByteArrayInputStream(pdf2))

        ) {
          //  PDFTextStripper stripper = new PDFTextStripper();
            String text1 = normalizeText(stripper.getText(doc1));
            String text2 = normalizeText(stripper.getText(doc2));
            return text1.equalsIgnoreCase(text2);
        }
            */
            String text1 = extractText(pdf1);
            String text2 = extractText(pdf2);
            return text1.equalsIgnoreCase(text2);
    }

    /**
     * Extracts and normalizes text from a PDF document.
     *
     * @param pdf The PDF document as a byte array.
     * @return The normalized text content.
     * @throws Exception if an error occurs while reading the PDF.
     */
    private static String extractText(byte[] pdf) throws Exception {
        if (pdf == null) {
            return "";
        }

        PdfReader reader = null;
        try {
            reader = new PdfReader(new ByteArrayInputStream(pdf));
            StringBuilder text = new StringBuilder();
            int pages = reader.getNumberOfPages();
            
            for (int i = 1; i <= pages; i++) {
                text.append(PdfTextExtractor.getTextFromPage(reader, i));
            }
            
            return normalizeText(text.toString());
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    /**
     * Normalizes text by trimming and collapsing all whitespace sequences to a single space.
     *
     * @param text The original text.
     * @return The normalized text.
     */
    private static String normalizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
