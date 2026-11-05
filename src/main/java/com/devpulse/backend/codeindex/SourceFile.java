package com.devpulse.backend.codeindex;

/** One file of a repository snapshot: its path in the repository and its text. */
public record SourceFile(String path, String content) {
}
