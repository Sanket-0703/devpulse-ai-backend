package com.devpulse.backend.codeindex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the source files out of a repository's zip archive (GitHub's
 * "zipball": every path starts with a folder like {@code owner-repo-1a2b3c/}).
 *
 * <p>The archive comes from the network, so reading it is bounded: files
 * over {@link #MAX_FILE_BYTES} are skipped, and reading stops after
 * {@link #MAX_FILES} files or {@link #MAX_TOTAL_BYTES} of text. A small zip
 * that expands to gigabytes (a "zip bomb") can't exhaust memory.
 */
public final class SourceArchive {

    static final int MAX_FILE_BYTES = 200_000;
    static final long MAX_TOTAL_BYTES = 50_000_000;
    static final int MAX_FILES = 3_000;

    /** File extension → language. Only these are indexed: code, not data or binaries. */
    static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("py", "python"), Map.entry("go", "go"), Map.entry("rb", "ruby"), Map.entry("rs", "rust"),
            Map.entry("cs", "csharp"), Map.entry("cpp", "cpp"), Map.entry("cc", "cpp"), Map.entry("c", "c"),
            Map.entry("h", "c"), Map.entry("hpp", "cpp"), Map.entry("php", "php"), Map.entry("swift", "swift"),
            Map.entry("scala", "scala"), Map.entry("sql", "sql"), Map.entry("sh", "shell"));

    /** Build output, dependencies and generated code: not written by the team, not worth searching. */
    private static final List<String> SKIPPED_DIRECTORIES = List.of(
            "node_modules/", "vendor/", "dist/", "build/", "target/", ".next/", ".git/", "coverage/", "out/");
    private static final Set<String> SKIPPED_SUFFIXES = Set.of(".min.js", ".d.ts", ".generated.ts", ".pb.go");

    private SourceArchive() {
    }

    public static List<SourceFile> read(byte[] zip) {
        List<SourceFile> files = new ArrayList<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null && files.size() < MAX_FILES && total < MAX_TOTAL_BYTES) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = withoutTopFolder(entry.getName());
                if (path == null || language(path) == null || isSkipped(path)) {
                    continue;
                }
                byte[] bytes = readAtMost(in, MAX_FILE_BYTES);
                if (bytes == null || looksBinary(bytes)) {
                    continue; // too big, or not text after all
                }
                total += bytes.length;
                files.add(new SourceFile(path, new String(bytes, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read the repository archive", e);
        }
        return files;
    }

    /** "java" for "src/App.java"; null for files that aren't indexed. */
    public static String language(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : LANGUAGES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static boolean isSkipped(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return SKIPPED_DIRECTORIES.stream().anyMatch(dir -> lower.startsWith(dir) || lower.contains("/" + dir))
                || SKIPPED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /** "owner-repo-1a2b3c/src/App.java" → "src/App.java". */
    static String withoutTopFolder(String name) {
        int slash = name.indexOf('/');
        return slash < 0 || slash == name.length() - 1 ? null : name.substring(slash + 1);
    }

    /** The entry's bytes, or null if it's longer than {@code max} (never reads more than max + 1 bytes). */
    private static byte[] readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > max) {
                return null;
            }
        }
        return out.toByteArray();
    }

    private static boolean looksBinary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
