package com.docintel.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Small JSON file helper so pipeline artifacts stay human-readable
 * (pretty-printed, UTF-8), matching the Python project's output files.
 */
public final class JsonFiles {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonFiles() {
    }

    public static void write(Path path, Object value) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, MAPPER.writeValueAsString(value));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static <T> List<T> readList(Path path, Class<T> type) {
        try {
            return MAPPER.readValue(Files.readString(path),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, type));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static Map<String, Object> readMap(Path path) {
        try {
            return MAPPER.readValue(Files.readString(path), new TypeReference<>() {
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static List<Map<String, Object>> readMapList(Path path) {
        try {
            return MAPPER.readValue(Files.readString(path), new TypeReference<>() {
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
