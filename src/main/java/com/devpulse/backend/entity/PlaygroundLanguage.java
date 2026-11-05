package com.devpulse.backend.entity;

/** Languages the Playground accepts; the extension names the snippet's file ("snippet.java"). */
public enum PlaygroundLanguage {

    JAVA("java"),
    KOTLIN("kt"),
    JAVASCRIPT("js"),
    TYPESCRIPT("ts"),
    PYTHON("py"),
    GO("go"),
    SQL("sql"),
    OTHER("txt");

    private final String extension;

    PlaygroundLanguage(String extension) {
        this.extension = extension;
    }

    public String fileName() {
        return "snippet." + extension;
    }
}
