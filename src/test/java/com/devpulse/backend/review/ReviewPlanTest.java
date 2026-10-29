package com.devpulse.backend.review;

import com.devpulse.backend.dto.github.PullRequestFilesResponse.ChangedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlanTest {

    @Test
    void parsingNumbersEveryLineInTheOldAndNewFile() {
        DiffFile file = DiffFile.parse("App.java", "modified",
                "@@ -10,3 +10,4 @@ class App {\n int a = 1;\n-int b = 2;\n+int b = 3;\n+int c = 4;\n int d = 5;\n");

        assertThat(file.lines()).extracting(DiffFile.Line::kind).containsExactly(DiffFile.Kind.HUNK,
                DiffFile.Kind.CONTEXT, DiffFile.Kind.DEL, DiffFile.Kind.ADD, DiffFile.Kind.ADD, DiffFile.Kind.CONTEXT);
        assertThat(file.addedLines()).extracting(DiffFile.Line::newLine).containsExactly(11, 12);
        assertThat(file.lines().get(2).oldLine()).isEqualTo(11);
        assertThat(file.commentableLines()).containsExactly(10, 11, 12, 13); // no removed line
    }

    @Test
    void removedBinaryAndGeneratedFilesAreSkipped() {
        ReviewPlan plan = ReviewPlan.of(List.of(
                file("src/App.java", "@@ -1 +1 @@\n+x"),
                new ChangedFile("src/Old.java", null, "removed", 0, 3, "@@ -1,3 +0,0 @@\n-a\n-b\n-c", null),
                file("logo.png", null),
                file("package-lock.json", "@@ -1 +1 @@\n+x"),
                file("web/dist/app.min.js", "@@ -1 +1 @@\n+x"),
                file("frontend/node_modules/x/index.js", "@@ -1 +1 @@\n+x")), 1000, 5);

        assertThat(plan.files()).extracting(DiffFile::path).containsExactly("src/App.java");
        assertThat(plan.filesSkipped()).isEqualTo(5);
    }

    @Test
    void smallFilesShareAChunkAndEveryLineCarriesItsNewNumber() {
        ReviewPlan plan = ReviewPlan.of(List.of(
                file("a.js", "@@ -1,2 +1,2 @@\n keep\n-old\n+new"),
                file("b.js", "@@ -0,0 +1 @@\n+hello")), 1000, 5);

        assertThat(plan.chunks()).hasSize(1);
        assertThat(plan.chunks().getFirst().paths()).containsExactly("a.js", "b.js");
        assertThat(plan.chunks().getFirst().text()).isEqualTo("""
                File: a.js
                @@ -1,2 +1,2 @@
                     1   keep
                       - old
                     2 + new

                File: b.js
                @@ -0,0 +1 @@
                     1 + hello
                """);
        assertThat(plan.truncated()).isFalse();
        assertThat(plan.reviewedPaths()).containsExactly("a.js", "b.js");
    }

    @Test
    void aBigFileIsSplitAtHunksAndLimitsMarkThePlanTruncated() {
        String big = "@@ -1 +1 @@\n+" + "a".repeat(60) + "\n@@ -50 +50 @@\n+" + "b".repeat(60);
        ReviewPlan split = ReviewPlan.of(List.of(file("big.txt", big)), 120, 5);

        assertThat(split.chunks()).extracting(c -> c.text().lines().findFirst().orElseThrow())
                .containsExactly("File: big.txt (part 1 of 2)", "File: big.txt (part 2 of 2)");
        assertThat(split.truncated()).isFalse();

        // Out of chunks: the second file is left out.
        ReviewPlan limited = ReviewPlan.of(List.of(file("a.txt", "@@ -1 +1 @@\n+" + "a".repeat(80)),
                file("b.txt", "@@ -1 +1 @@\n+" + "b".repeat(80))), 120, 1);
        assertThat(limited.chunks()).hasSize(1);
        assertThat(limited.reviewedPaths()).containsExactly("a.txt");
        assertThat(limited.truncated()).isTrue();

        // One hunk bigger than a chunk: cut.
        ReviewPlan cut = ReviewPlan.of(List.of(file("huge.txt", "@@ -1 +1 @@\n+" + "x".repeat(500))), 100, 5);
        assertThat(cut.chunks().getFirst().text()).hasSize(100);
        assertThat(cut.truncated()).isTrue();
    }

    private static ChangedFile file(String name, String patch) {
        return new ChangedFile(name, null, "modified", 1, 1, patch, null);
    }
}
