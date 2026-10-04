package com.docintel.preprocessing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docintel.config.AppProperties;
import com.docintel.ingestion.PdfIngestionService;

/**
 * Step 2: clean extracted document text (PDF encoding artifacts, whitespace)
 * while preserving document structure.
 */
@Service
public class TextCleanerService {

    private static final Logger log = LoggerFactory.getLogger(TextCleanerService.class);

    private static final Map<String, String> COMMON_TEXT_REPLACEMENTS = new LinkedHashMap<>();

    static {
        COMMON_TEXT_REPLACEMENTS.put("—", "-");
        COMMON_TEXT_REPLACEMENTS.put("–", "-");
        COMMON_TEXT_REPLACEMENTS.put("‘", "'");
        COMMON_TEXT_REPLACEMENTS.put("’", "'");
        COMMON_TEXT_REPLACEMENTS.put("“", "\"");
        COMMON_TEXT_REPLACEMENTS.put("”", "\"");
        COMMON_TEXT_REPLACEMENTS.put("×", "x");
        COMMON_TEXT_REPLACEMENTS.put("â€”", "-");
        COMMON_TEXT_REPLACEMENTS.put("â€“", "-");
        COMMON_TEXT_REPLACEMENTS.put("â€˜", "'");
        COMMON_TEXT_REPLACEMENTS.put("â€™", "'");
        COMMON_TEXT_REPLACEMENTS.put("â€œ", "\"");
        COMMON_TEXT_REPLACEMENTS.put("Ã—", "x");
        COMMON_TEXT_REPLACEMENTS.put("Â", "");
    }

    private final AppProperties props;

    public TextCleanerService(AppProperties props) {
        this.props = props;
    }

    public String cleanText(String text) {
        String cleaned = text;
        for (Map.Entry<String, String> entry : COMMON_TEXT_REPLACEMENTS.entrySet()) {
            cleaned = cleaned.replace(entry.getKey(), entry.getValue());
        }

        // Normalize tabs/spaces without removing line breaks used as structure.
        cleaned = cleaned.replaceAll("[ \\t]+", " ");
        // Reduce large blank areas to a maximum of one blank line.
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");

        List<String> lines = new ArrayList<>();
        for (String line : cleaned.split("\n", -1)) {
            lines.add(line.strip());
        }
        return String.join("\n", lines).strip();
    }

    public List<Path> cleanExtractedDocuments() {
        Path extractedTextDir = props.processedDataDir().resolve("extracted_text");
        Path cleanedDir = props.processedDataDir().resolve("cleaned_documents");
        List<Path> cleanedFiles = new ArrayList<>();

        if (!Files.isDirectory(extractedTextDir)) {
            log.warn("No extracted text folder found in {}", extractedTextDir);
            return cleanedFiles;
        }

        try {
            Files.createDirectories(cleanedDir);
            for (Path extractedFile : PdfIngestionService.listFiles(extractedTextDir, "*.txt", false)) {
                Path outputPath = cleanedDir.resolve(extractedFile.getFileName());
                if (Files.exists(outputPath)) {
                    cleanedFiles.add(outputPath);
                    continue;
                }

                String cleanedText = cleanText(Files.readString(extractedFile));
                Files.writeString(outputPath, cleanedText);
                cleanedFiles.add(outputPath);
                log.info("Cleaned document text created: {}", outputPath);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        return cleanedFiles;
    }
}
