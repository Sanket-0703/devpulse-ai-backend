package com.devpulse.backend.codeindex;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceArchiveTest {

    @Test
    void keepsSourceCodeAndSkipsEverythingElse() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("src/App.java", text("class App {}"));
        files.put("web/src/main.tsx", text("export {}"));
        files.put("README.md", text("# docs"));                         // not code
        files.put("application.properties", text("password=x"));        // not code (and may hold secrets)
        files.put("frontend/node_modules/react/index.js", text("x"));   // dependency
        files.put("target/classes/Gen.java", text("x"));                // build output
        files.put("web/src/schema.d.ts", text("x"));                    // generated types
        files.put("img/logo.java", new byte[]{1, 0, 2});                 // binary despite the name
        files.put("src/Huge.java", new byte[SourceArchive.MAX_FILE_BYTES + 1]);

        assertThat(SourceArchive.read(Zips.zipball(files)))
                .extracting(SourceFile::path)
                .containsExactly("src/App.java", "web/src/main.tsx");
    }

    @Test
    void theTopFolderOfTheZipballIsNotPartOfThePath() {
        assertThat(SourceArchive.withoutTopFolder("owner-repo-1a2b3c/src/App.java")).isEqualTo("src/App.java");
        assertThat(SourceArchive.withoutTopFolder("owner-repo-1a2b3c/")).isNull();
        assertThat(SourceArchive.language("src/App.JAVA")).isEqualTo("java");
        assertThat(SourceArchive.language("Makefile")).isNull();
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
