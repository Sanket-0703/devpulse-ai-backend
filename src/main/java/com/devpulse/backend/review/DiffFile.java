package com.devpulse.backend.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One file's unified diff, parsed: every line with its number in the old
 * and/or new version of the file (the same rules as the frontend's
 * parsePatch). Findings point at {@code newLine} numbers.
 *
 * @param status GitHub's file status: added, modified, renamed, removed…
 */
public record DiffFile(String path, String status, List<Line> lines) {

    public enum Kind { HUNK, CONTEXT, ADD, DEL, NOTE }

    /** A diff line; {@code oldLine}/{@code newLine} are null where they don't apply. */
    public record Line(Kind kind, String content, Integer oldLine, Integer newLine) {
    }

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    public static DiffFile parse(String path, String status, String patch) {
        List<Line> lines = new ArrayList<>();
        int oldLine = 0;
        int newLine = 0;
        String[] rows = patch.split("\n", -1);
        int count = rows.length > 0 && rows[rows.length - 1].isEmpty() ? rows.length - 1 : rows.length;

        for (int i = 0; i < count; i++) {
            String raw = rows[i];
            Matcher header = HUNK_HEADER.matcher(raw);
            if (header.find()) {
                oldLine = Integer.parseInt(header.group(1));
                newLine = Integer.parseInt(header.group(2));
                lines.add(new Line(Kind.HUNK, raw, null, null));
            } else if (raw.startsWith("+")) {
                lines.add(new Line(Kind.ADD, raw.substring(1), null, newLine++));
            } else if (raw.startsWith("-")) {
                lines.add(new Line(Kind.DEL, raw.substring(1), oldLine++, null));
            } else if (raw.startsWith("\\")) {
                lines.add(new Line(Kind.NOTE, raw.substring(1).trim(), null, null));
            } else if (!lines.isEmpty()) {
                // A context line starts with a space; an empty one may have lost it.
                lines.add(new Line(Kind.CONTEXT, raw.startsWith(" ") ? raw.substring(1) : raw, oldLine++, newLine++));
            }
        }
        return new DiffFile(path, status, List.copyOf(lines));
    }

    public List<Line> addedLines() {
        return lines.stream().filter(l -> l.kind() == Kind.ADD).toList();
    }

    /** Lines a finding may point at: those that exist in the new version and appear in the diff. */
    public Set<Integer> commentableLines() {
        Set<Integer> numbers = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line.newLine() != null) {
                numbers.add(line.newLine());
            }
        }
        return numbers;
    }

    /** The diff split into its hunks, each starting with its "@@" header line. */
    public List<List<Line>> hunks() {
        List<List<Line>> hunks = new ArrayList<>();
        for (Line line : lines) {
            if (line.kind() == Kind.HUNK || hunks.isEmpty()) {
                hunks.add(new ArrayList<>());
            }
            hunks.getLast().add(line);
        }
        return hunks;
    }
}
