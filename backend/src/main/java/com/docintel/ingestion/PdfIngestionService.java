package com.docintel.ingestion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docintel.config.AppProperties;

/**
 * Step 1 of the pipeline: extract the text of every PDF under data/raw.
 * One PDF = one document; the document id is the sanitized file stem.
 * Pages that carry images instead of extractable text are OCRed with
 * Tesseract, so scanned PDFs are supported too.
 */
@Service
public class PdfIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PdfIngestionService.class);
    private static final Pattern UNSAFE_ID_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    /** Below this many characters of extracted text, a page is treated as image-only. */
    private static final int OCR_TEXT_THRESHOLD = 20;
    private static final int OCR_RENDER_DPI = 200;

    private final AppProperties props;
    private final OcrService ocrService;

    public PdfIngestionService(AppProperties props, OcrService ocrService) {
        this.props = props;
        this.ocrService = ocrService;
    }

    /** Stable document id derived from the PDF filename. */
    public static String documentIdFor(Path pdfPath) {
        String stem = stripExtension(pdfPath.getFileName().toString());
        return UNSAFE_ID_CHARS.matcher(stem).replaceAll("_");
    }

    /** Extract the full text of one PDF, OCRing image-only pages. */
    public String extractTextFromPdf(Path pdfPath) throws IOException {
        StringBuilder textByPage = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String pageText = stripper.getText(document);

                if (pageText.strip().length() < OCR_TEXT_THRESHOLD && ocrService.isAvailable()) {
                    BufferedImage pageImage = renderer.renderImageWithDPI(pageNumber - 1, OCR_RENDER_DPI);
                    String ocrText = ocrService.ocrImage(pageImage);
                    if (!ocrText.isBlank()) {
                        log.info("OCR used for page {} of {}", pageNumber, pdfPath.getFileName());
                        pageText = ocrText;
                    }
                }

                textByPage.append("\n ").append(pageNumber).append(" ---\n").append(pageText).append('\n');
            }
        }
        return sanitizeExtractedText(textByPage.toString());
    }

    /**
     * Some PDFs produce NUL bytes and other control characters during text
     * extraction; PostgreSQL rejects 0x00 in text columns. Keep only
     * printable characters plus newlines and tabs.
     */
    public static String sanitizeExtractedText(String text) {
        return text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "").strip();
    }

    /** Extract text from every PDF under data/raw: one .txt per document. */
    public List<Path> processPdfs() {
        Path rawDir = props.rawDataDir();
        Path textOutputDir = props.processedDataDir().resolve("extracted_text");
        List<Path> outputFiles = new ArrayList<>();

        List<Path> pdfFiles = listFiles(rawDir, "*.pdf", true);
        if (pdfFiles.isEmpty()) {
            log.warn("No PDF files found in {}", rawDir);
            return outputFiles;
        }

        try {
            Files.createDirectories(textOutputDir);
            for (Path pdfPath : pdfFiles) {
                String documentId = documentIdFor(pdfPath);
                Path outputPath = textOutputDir.resolve(documentId + ".txt");

                if (Files.exists(outputPath)) {
                    outputFiles.add(outputPath);
                    continue;
                }

                String extractedText = extractTextFromPdf(pdfPath);
                Files.writeString(outputPath, extractedText);
                outputFiles.add(outputPath);
                log.info("Processed: {} ({})", pdfPath.getFileName(), documentId);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        return outputFiles;
    }

    public static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    public static List<Path> listFiles(Path dir, String glob, boolean recursive) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        String suffix = glob.replace("*", "");
        try (Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
