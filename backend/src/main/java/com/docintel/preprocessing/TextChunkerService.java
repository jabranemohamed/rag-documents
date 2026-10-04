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
import com.docintel.util.JsonFiles;

/**
 * Steps 3 + 4: split cleaned document text into overlapping chunks and
 * validate chunk quality before embedding.
 */
@Service
public class TextChunkerService {

    private static final Logger log = LoggerFactory.getLogger(TextChunkerService.class);

    private final AppProperties props;

    public TextChunkerService(AppProperties props) {
        this.props = props;
    }

    public List<String> createTextChunks(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be smaller than chunkSize");
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = start + chunkSize - overlap;
        }
        return chunks;
    }

    public List<ChunkRecord> buildChunkRecords(String documentId, String sourceFile, List<String> chunks) {
        List<ChunkRecord> records = new ArrayList<>();
        for (int index = 1; index <= chunks.size(); index++) {
            String chunkText = chunks.get(index - 1);
            records.add(new ChunkRecord(
                    documentId,
                    "%s_chunk_%03d".formatted(documentId, index),
                    index,
                    sourceFile,
                    chunkText,
                    chunkText.length()
            ));
        }
        return records;
    }

    /** Create one chunk JSON file per cleaned document. */
    public List<Path> chunkCleanedDocuments() {
        Path cleanedDir = props.processedDataDir().resolve("cleaned_documents");
        Path chunksOutputDir = props.processedDataDir().resolve("chunks");
        List<Path> chunkFiles = new ArrayList<>();

        if (!Files.isDirectory(cleanedDir)) {
            log.warn("No cleaned documents folder found in {}", cleanedDir);
            return chunkFiles;
        }

        try {
            Files.createDirectories(chunksOutputDir);
            for (Path cleanedFile : PdfIngestionService.listFiles(cleanedDir, "*.txt", false)) {
                String documentId = PdfIngestionService.stripExtension(cleanedFile.getFileName().toString());
                Path outputPath = chunksOutputDir.resolve(documentId + "_chunks.json");

                if (Files.exists(outputPath)) {
                    chunkFiles.add(outputPath);
                    continue;
                }

                String cleanedText = Files.readString(cleanedFile);
                List<String> chunks = createTextChunks(cleanedText, props.chunkSize(), props.chunkOverlap());
                List<ChunkRecord> chunkRecords =
                        buildChunkRecords(documentId, cleanedFile.getFileName().toString(), chunks);

                JsonFiles.write(outputPath, chunkRecords);
                chunkFiles.add(outputPath);
                log.info("Chunk file created: {} ({} chunks)", outputPath, chunkRecords.size());
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        return chunkFiles;
    }

    public Map<String, Object> validateChunkRecords(List<ChunkRecord> chunkRecords) {
        int emptyTextCount = 0;
        List<Integer> chunkLengths = new ArrayList<>();

        for (ChunkRecord record : chunkRecords) {
            String chunkText = record.text() == null ? "" : record.text();
            if (chunkText.strip().isEmpty()) {
                emptyTextCount++;
            }
            chunkLengths.add(chunkText.length());
        }

        double avg = chunkLengths.isEmpty()
                ? 0
                : Math.round(chunkLengths.stream().mapToInt(Integer::intValue).average().orElse(0) * 100) / 100.0;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("chunk_count", chunkRecords.size());
        report.put("min_chunk_length", chunkLengths.stream().mapToInt(Integer::intValue).min().orElse(0));
        report.put("max_chunk_length", chunkLengths.stream().mapToInt(Integer::intValue).max().orElse(0));
        report.put("avg_chunk_length", avg);
        report.put("empty_text_count", emptyTextCount);
        report.put("is_valid", emptyTextCount == 0);
        return report;
    }

    /** Create a validation report for each chunk JSON file. */
    public List<Path> validateChunkFiles() {
        Path chunksDir = props.processedDataDir().resolve("chunks");
        Path reportsDir = props.processedDataDir().resolve("chunk_reports");
        List<Path> reportFiles = new ArrayList<>();

        if (!Files.isDirectory(chunksDir)) {
            log.warn("No chunks folder found in {}", chunksDir);
            return reportFiles;
        }

        try {
            Files.createDirectories(reportsDir);
            for (Path chunkFile : PdfIngestionService.listFiles(chunksDir, "*_chunks.json", false)) {
                String stem = PdfIngestionService.stripExtension(chunkFile.getFileName().toString());
                Path outputPath = reportsDir.resolve(stem + "_report.json");

                if (Files.exists(outputPath)) {
                    reportFiles.add(outputPath);
                    continue;
                }

                List<ChunkRecord> chunkRecords = JsonFiles.readList(chunkFile, ChunkRecord.class);
                Map<String, Object> report = validateChunkRecords(chunkRecords);
                report.put("chunk_file", chunkFile.getFileName().toString());

                JsonFiles.write(outputPath, report);
                reportFiles.add(outputPath);
                log.info("Chunk validation report created: {} (valid={})", outputPath, report.get("is_valid"));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        return reportFiles;
    }
}
