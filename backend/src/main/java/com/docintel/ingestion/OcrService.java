package com.docintel.ingestion;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.sourceforge.tess4j.Tesseract;

/**
 * OCR for PDF pages that carry images instead of extractable text.
 * Uses the local Tesseract engine (brew install tesseract); when the engine
 * or its language data is unavailable, OCR is disabled gracefully and the
 * pipeline continues with text-only extraction.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final Tesseract tesseract;
    private volatile boolean available;

    public OcrService() {
        this.tesseract = new Tesseract();
        String dataPath = System.getenv().getOrDefault("TESSDATA_PREFIX", "/opt/homebrew/share/tessdata");
        String language = System.getenv().getOrDefault("OCR_LANGUAGE", "eng");
        this.tesseract.setDatapath(dataPath);
        this.tesseract.setLanguage(language);
        this.available = Files.isDirectory(Path.of(dataPath));
        if (!available) {
            log.warn("Tesseract data not found at {} - OCR disabled. "
                    + "Install with: brew install tesseract", dataPath);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** OCR one rendered PDF page; returns an empty string when OCR is unavailable or fails. */
    public String ocrImage(BufferedImage image) {
        if (!available) {
            return "";
        }
        try {
            String text = tesseract.doOCR(image);
            return text == null ? "" : text;
        } catch (Throwable ex) {
            log.warn("OCR failed, disabling OCR for this run: {}", ex.getMessage());
            available = false;
            return "";
        }
    }
}
