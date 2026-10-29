package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AIReviewFinding;
import com.devpulse.backend.dto.AIReviewResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockAIProviderTest {

    private final MockAIProvider mock = new MockAIProvider();

    @Test
    void reviewsOnlyTheAddedLinesOfADiffAndNamesTheirFileAndLine() {
        AIReviewResult result = mock.review("""
                File: src/App.java (part 2 of 2)
                @@ -40,2 +40,3 @@
                    40   System.out.println("context, not new");
                       - console.log("removed");
                    41 + try { run(); } catch (Exception e) {}
                    42 + // TODO remove
                """);

        assertThat(result.findings()).extracting(AIReviewFinding::title, AIReviewFinding::file, AIReviewFinding::line)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Exception swallowed", "src/App.java", 41),
                        org.assertj.core.groups.Tuple.tuple("Unfinished work", "src/App.java", 42));
        assertThat(result.promptTokens()).isGreaterThan(600); // system prompt + input, ~4 chars per token
        assertThat(result.completionTokens()).isEqualTo(20 + 2 * 60);
    }

    @Test
    void aPlainSnippetUsesItsOwnLineNumbers() {
        AIReviewResult result = mock.review("int x = 1;\nThread.sleep(100);\n" + "y".repeat(170));

        assertThat(result.findings()).extracting(AIReviewFinding::title, AIReviewFinding::line)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Blocking sleep", 2),
                        org.assertj.core.groups.Tuple.tuple("Very long line", 3));
        assertThat(result.findings().getFirst().file()).isNull();
        assertThat(mock.name()).isEqualTo("mock");
    }
}
