package com.devpulse.backend.codeindex;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a source file into chunks small enough for the embedding model
 * (all-MiniLM-L6-v2 reads about 256 word pieces; longer text is cut off, so
 * the end of a long chunk would be invisible to search).
 *
 * <p>A chunk grows line by line up to {@link #MAX_LINES} lines or
 * {@link #MAX_CHARS} characters. When it's full, it ends at the last blank
 * line in its second half if there is one, so methods and classes tend to
 * stay together. Chunks overlap by {@link #OVERLAP_LINES} lines, so code on
 * a boundary appears whole in one of them.
 */
public final class CodeChunker {

    static final int MAX_LINES = 40;
    static final int MAX_CHARS = 1_000;
    static final int OVERLAP_LINES = 3;
    /** Minified or generated lines are cut, so one line can't fill a chunk on its own. */
    static final int MAX_LINE_CHARS = 300;

    private CodeChunker() {
    }

    public static List<CodeChunk> chunk(SourceFile file) {
        String language = SourceArchive.language(file.path());
        String text = file.content().replace("\r\n", "\n");
        // A final newline ends the last line; it doesn't start an extra, empty one.
        String[] lines = (text.endsWith("\n") ? text.substring(0, text.length() - 1) : text).split("\n", -1);
        List<CodeChunk> chunks = new ArrayList<>();

        int start = 0;
        while (start < lines.length) {
            int end = start; // exclusive
            int chars = 0;
            while (end < lines.length && end - start < MAX_LINES) {
                int length = Math.min(lines[end].length(), MAX_LINE_CHARS) + 1;
                if (chars + length > MAX_CHARS && end > start) {
                    break;
                }
                chars += length;
                end++;
            }
            if (end < lines.length) {
                end = preferBlankLine(lines, start, end);
            }
            String content = join(lines, start, end);
            if (!content.isBlank()) {
                chunks.add(new CodeChunk(file.path(), start + 1, end, language, content));
            }
            if (end >= lines.length) {
                break;
            }
            start = Math.max(end - OVERLAP_LINES, start + 1);
        }
        return chunks;
    }

    /** End after the last blank line in the second half of [start, end), if any. */
    private static int preferBlankLine(String[] lines, int start, int end) {
        for (int i = end - 1; i >= start + (end - start) / 2; i--) {
            if (lines[i].isBlank()) {
                return i + 1;
            }
        }
        return end;
    }

    private static String join(String[] lines, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i < end; i++) {
            String line = lines[i];
            text.append(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line);
            if (i < end - 1) {
                text.append('\n');
            }
        }
        return text.toString().stripTrailing();
    }
}
