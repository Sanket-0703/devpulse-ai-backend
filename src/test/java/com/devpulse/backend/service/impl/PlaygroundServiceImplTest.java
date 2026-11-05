package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.github.PullRequestFilesResponse.ChangedFile;
import com.devpulse.backend.review.DiffFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundServiceImplTest {

    @Test
    void aSnippetBecomesANewFileWhoseLineNumbersAreTheSnippets() {
        ChangedFile file = PlaygroundServiceImpl.asNewFile("snippet.py", "a = 1\r\nb = 2\n\nc = 3");

        assertThat(file.filename()).isEqualTo("snippet.py");
        assertThat(file.status()).isEqualTo("added");
        assertThat(file.patch()).isEqualTo("@@ -0,0 +1,4 @@\n+a = 1\n+b = 2\n+\n+c = 3");

        DiffFile parsed = DiffFile.parse(file.filename(), file.status(), file.patch());
        assertThat(parsed.addedLines()).extracting(DiffFile.Line::newLine).containsExactly(1, 2, 3, 4);
    }
}
