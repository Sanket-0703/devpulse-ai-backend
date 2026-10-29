package com.devpulse.backend.review;

import com.devpulse.backend.dto.github.PullRequestFilesResponse.ChangedFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a review will look at: the parsed diffs worth reviewing, packed into
 * chunks that each fit one AI request.
 *
 * <p>Why chunks: a model has a limited context window, and cost grows with
 * input size. Small files share a chunk; a big file is split at hunk
 * boundaries so a chunk never cuts a hunk in half (unless one hunk alone is
 * too big, which is then cut and marked truncated).
 *
 * @param files         every reviewable file, parsed (also used by the security rules)
 * @param chunks        the text sent to the AI, one request per chunk
 * @param filesSkipped  removed, binary and generated files
 * @param truncated     true if limits left part of the diff out
 */
public record ReviewPlan(List<DiffFile> files, List<Chunk> chunks, int filesSkipped, boolean truncated) {

    /** @param paths the files (at least partly) in this chunk */
    public record Chunk(List<String> paths, String text) {
    }

    /** Lock files, build output, minified and vendored code: generated, not written by hand. */
    private static final List<String> GENERATED_SUFFIXES = List.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", ".lock", ".min.js", ".min.css", ".map", ".snap",
            ".svg");
    private static final List<String> GENERATED_DIRECTORIES = List.of(
            "node_modules/", "vendor/", "dist/", "build/", "target/", ".next/");

    public static ReviewPlan of(List<ChangedFile> changedFiles, int maxChunkChars, int maxChunks) {

        List<DiffFile> files = new ArrayList<>();
        int skipped = 0;
        for (ChangedFile file : changedFiles) {
            if (!isReviewable(file)) {
                skipped++;
                continue;
            }
            files.add(DiffFile.parse(file.filename(), file.status(), file.patch()));
        }

        Packer packer = new Packer(maxChunkChars, maxChunks);
        for (DiffFile file : files) {
            packer.add(file);
        }
        return new ReviewPlan(List.copyOf(files), packer.chunks(), skipped, packer.truncated);
    }

    static boolean isReviewable(ChangedFile file) {
        if (file.patch() == null || file.patch().isBlank() || "removed".equals(file.status())) {
            return false;
        }
        String path = file.filename().toLowerCase(Locale.ROOT);
        return GENERATED_SUFFIXES.stream().noneMatch(path::endsWith)
                && GENERATED_DIRECTORIES.stream().noneMatch(dir -> path.startsWith(dir) || path.contains("/" + dir));
    }

    /** The files whose diff made it into at least one chunk. */
    public Set<String> reviewedPaths() {
        return chunks.stream().flatMap(c -> c.paths().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Map<String, DiffFile> filesByPath() {
        return files.stream().collect(Collectors.toMap(DiffFile::path, Function.identity(), (a, b) -> a));
    }

    // ---------- rendering ----------

    /**
     * How a diff is shown to the model: each line starts with its number in
     * the new file, so findings can name a line. Removed lines have no new
     * number and can't be the target of a finding.
     * <pre>
     *    10   const a = 1
     *    11 + const b = 3
     *       - const b = 2
     * </pre>
     */
    static String render(List<DiffFile.Line> lines) {
        StringBuilder text = new StringBuilder();
        for (DiffFile.Line line : lines) {
            switch (line.kind()) {
                case HUNK -> text.append(line.content()).append('\n');
                case ADD -> text.append(String.format("%6d + %s%n", line.newLine(), line.content()));
                case CONTEXT -> text.append(String.format("%6d   %s%n", line.newLine(), line.content()));
                case DEL -> text.append(String.format("%6s - %s%n", "", line.content()));
                case NOTE -> { /* "\ No newline at end of file" doesn't help a reviewer */ }
            }
        }
        return text.toString();
    }

    /** Greedy packing: fill the current chunk, start a new one when the next piece doesn't fit. */
    private static final class Packer {

        private final int maxChars;
        private final int maxChunks;
        private final List<Chunk> chunks = new ArrayList<>();
        private StringBuilder text = new StringBuilder();
        private final Set<String> paths = new LinkedHashSet<>();
        private boolean truncated;

        Packer(int maxChars, int maxChunks) {
            this.maxChars = maxChars;
            this.maxChunks = maxChunks;
        }

        void add(DiffFile file) {
            String whole = "File: " + file.path() + "\n" + render(file.lines());
            if (whole.length() <= maxChars) {
                append(file.path(), whole);
                return;
            }
            // Too big for one chunk: one piece per hunk.
            List<List<DiffFile.Line>> hunks = file.hunks();
            for (int i = 0; i < hunks.size(); i++) {
                String piece = "File: " + file.path() + " (part " + (i + 1) + " of " + hunks.size() + ")\n"
                        + render(hunks.get(i));
                if (piece.length() > maxChars) {
                    piece = piece.substring(0, maxChars);
                    truncated = true;
                }
                append(file.path(), piece);
            }
        }

        private void append(String path, String piece) {
            if (!text.isEmpty() && text.length() + piece.length() + 1 > maxChars) {
                flush();
            }
            if (chunks.size() >= maxChunks) {
                truncated = true; // the review stops at maxChunks AI calls
                return;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(piece);
            paths.add(path);
        }

        private void flush() {
            if (!text.isEmpty()) {
                chunks.add(new Chunk(List.copyOf(paths), text.toString()));
                text = new StringBuilder();
                paths.clear();
            }
        }

        List<Chunk> chunks() {
            if (chunks.size() < maxChunks) {
                flush();
            }
            return List.copyOf(chunks);
        }
    }
}
