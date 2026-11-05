package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.github.GitHubCreateReviewRequest;
import com.devpulse.backend.entity.FindingCategory;
import com.devpulse.backend.entity.FindingSource;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.entity.ReviewSettings;
import com.devpulse.backend.entity.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a published review looks like; posting it to GitHub runs in WebhookIntegrationTest. */
class ReviewPublishingServiceImplTest {

    @Test
    void lineFindingsBecomeInlineCommentsAndTheRestGoesIntoTheSummary() {
        Review review = review();
        review.addFinding(finding(Severity.HIGH, "SQL built from strings", "Dao.java", 12, "sql-concatenation"));
        review.addFinding(finding(Severity.MEDIUM, "No tests for the new query", "Dao.java", null, null));
        review.addFinding(finding(Severity.LOW, "Debug output left in", "Dao.java", 13, null)); // below MEDIUM
        ReviewFinding dismissed = finding(Severity.CRITICAL, "False alarm", "Dao.java", 3, null);
        dismissed.setDismissed(true);
        review.addFinding(dismissed);

        GitHubCreateReviewRequest request = ReviewPublishingServiceImpl.request(review, settings(Severity.MEDIUM));

        assertThat(request.commitId()).isEqualTo("abcdef1234567890");
        assertThat(request.event()).isEqualTo("COMMENT");
        assertThat(request.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.path()).isEqualTo("Dao.java");
            assertThat(comment.line()).isEqualTo(12);
            assertThat(comment.side()).isEqualTo("RIGHT");
            assertThat(comment.body()).startsWith("**High · Security** — SQL built from strings")
                    .contains("**Fix:** Fix it.")
                    .endsWith("<sub>DevPulse AI · security rule `sql-concatenation`</sub>");
        });
        assertThat(request.body())
                .startsWith("### DevPulse AI review\n\nReviewed commit `abcdef1`: 2 findings (1 high, 1 medium).")
                .contains("**Not on a specific line:**\n\n- **Medium** `Dao.java` — No tests for the new query")
                .doesNotContain("Debug output") // below the minimum severity
                .doesNotContain("False alarm"); // dismissed
    }

    @Test
    void moreThanThirtyLineFindingsSpillIntoTheSummary() {
        Review review = review();
        for (int line = 1; line <= 32; line++) {
            review.addFinding(finding(Severity.HIGH, "Finding " + line, "A.java", line, null));
        }

        GitHubCreateReviewRequest request = ReviewPublishingServiceImpl.request(review, settings(Severity.LOW));

        assertThat(request.comments()).hasSize(ReviewPublishingServiceImpl.MAX_INLINE_COMMENTS);
        assertThat(request.body()).contains("`A.java:31` — Finding 31").contains("`A.java:32` — Finding 32");
    }

    @Test
    void aCleanReviewSaysSo() {
        Review review = review();
        review.setTruncated(true);

        GitHubCreateReviewRequest request = ReviewPublishingServiceImpl.request(review, settings(Severity.HIGH));

        assertThat(request.comments()).isEmpty();
        assertThat(request.body()).contains("no findings at high severity or above")
                .contains("_Part of this pull request wasn't reviewed (size or cost limit)._");
    }

    private static Review review() {
        Review review = new Review();
        review.setHeadSha("abcdef1234567890");
        return review;
    }

    private static ReviewSettings settings(Severity minimum) {
        ReviewSettings settings = new ReviewSettings(1L);
        settings.setMinPublishSeverity(minimum);
        return settings;
    }

    private static ReviewFinding finding(Severity severity, String title, String path, Integer line, String ruleId) {
        ReviewFinding finding = new ReviewFinding();
        finding.setSource(ruleId == null ? FindingSource.AI : FindingSource.RULE);
        finding.setRuleId(ruleId);
        finding.setSeverity(severity);
        finding.setCategory(ruleId == null ? FindingCategory.BUG : FindingCategory.SECURITY);
        finding.setTitle(title);
        finding.setDescription(title + ".");
        finding.setRecommendation("Fix it.");
        finding.setFilePath(path);
        finding.setLine(line);
        return finding;
    }
}
