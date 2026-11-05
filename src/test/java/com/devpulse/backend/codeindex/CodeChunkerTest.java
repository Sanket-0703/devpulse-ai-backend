package com.devpulse.backend.codeindex;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class CodeChunkerTest {

    @Test
    void aSmallFileIsOneChunkWithItsLineNumbers() {
        List<CodeChunk> chunks = CodeChunker.chunk(new SourceFile("src/App.java", "class App {\n  int x;\n}\n"));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.startLine()).isEqualTo(1);
            assertThat(chunk.endLine()).isEqualTo(3); // a final newline isn't an extra line
            assertThat(chunk.language()).isEqualTo("java");
            assertThat(chunk.content()).isEqualTo("class App {\n  int x;\n}");
            assertThat(chunk.embeddingText()).startsWith("File: src/App.java\nclass App {");
        });
    }

    @Test
    void aLongFileIsSplitPreferablyAtBlankLinesWithAFewLinesOfOverlap() {
        // 3 methods of 20 lines, separated by blank lines: 62 lines.
        String code = IntStream.range(0, 3)
                .mapToObj(m -> IntStream.range(0, 20).mapToObj(i -> "  line " + m + "." + i)
                        .collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n\n"));

        List<CodeChunk> chunks = CodeChunker.chunk(new SourceFile("A.java", code));

        // Each chunk ends at the blank line after a method; the next starts 3 lines earlier (overlap).
        assertThat(chunks).extracting(CodeChunk::startLine, CodeChunk::endLine).containsExactly(
                tuple(1, 21),
                tuple(19, 42),
                tuple(40, 62));
        assertThat(chunks).allSatisfy(c -> assertThat(c.endLine() - c.startLine() + 1)
                .isLessThanOrEqualTo(CodeChunker.MAX_LINES));
    }

    @Test
    void chunksStayUnderTheCharacterLimitAndHugeLinesAreCut() {
        String minified = "x".repeat(5_000);
        String code = minified + "\n" + "y = 1;\n".repeat(200);

        List<CodeChunk> chunks = CodeChunker.chunk(new SourceFile("app.min.ts", code));

        assertThat(chunks.getFirst().content().lines().findFirst().orElseThrow())
                .hasSize(CodeChunker.MAX_LINE_CHARS);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length())
                .isLessThanOrEqualTo(CodeChunker.MAX_CHARS));
        assertThat(chunks.getLast().endLine()).isEqualTo(201);
    }

    @Test
    void blankFilesProduceNothing() {
        assertThat(CodeChunker.chunk(new SourceFile("Empty.java", "\n\n   \n"))).isEmpty();
    }
}
