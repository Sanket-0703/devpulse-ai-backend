package com.devpulse.backend.codeindex;

/**
 * A piece of a source file: what gets embedded and returned by search.
 *
 * @param startLine first line, 1-based
 * @param endLine   last line, inclusive
 */
public record CodeChunk(String path, int startLine, int endLine, String language, String content) {

    /**
     * The text that is embedded. The path goes first: "security/JwtService.java"
     * says a lot about what the code is for, and searches match on it too.
     */
    public String embeddingText() {
        return "File: " + path + "\n" + content;
    }
}
