package com.devpulse.backend.review;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.AIReviewFinding;
import com.devpulse.backend.entity.FindingCategory;
import com.devpulse.backend.entity.FindingSource;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.entity.Severity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FindingsTest {

    private static final DiffFile APP = DiffFile.parse("src/App.java", "modified",
            "@@ -1,2 +1,3 @@\n line one\n+line two\n line three");
    private static final Map<String, DiffFile> FILES = Map.of("src/App.java", APP);
    private static final ReviewPlan.Chunk CHUNK = new ReviewPlan.Chunk(List.of("src/App.java"), "…");

    @Test
    void aValidFindingIsKeptAsIs() {
        ReviewFinding finding = Findings.fromAI(ai("HIGH", "BUG", "b/src/App.java", 2), CHUNK, FILES).orElseThrow();

        assertThat(finding.getSource()).isEqualTo(FindingSource.AI);
        assertThat(finding.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(finding.getCategory()).isEqualTo(FindingCategory.BUG);
        assertThat(finding.getFilePath()).isEqualTo("src/App.java"); // "b/" prefix removed
        assertThat(finding.getLine()).isEqualTo(2);
    }

    @Test
    void whatTheModelGetsWrongIsCorrectedOrDropped() {
        // Unknown severity/category fall back; a line outside the diff becomes "whole file".
        ReviewFinding odd = Findings.fromAI(ai("urgent", "style", "src/App.java (part 1 of 2)", 99), CHUNK, FILES)
                .orElseThrow();
        assertThat(odd.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(odd.getCategory()).isEqualTo(FindingCategory.CODE_QUALITY);
        assertThat(odd.getLine()).isNull();

        // No file named: the chunk only had one, so that's it.
        assertThat(Findings.fromAI(ai("LOW", "BUG", null, 1), CHUNK, FILES)).isPresent();

        // A file the model wasn't shown, or no title: dropped.
        assertThat(Findings.fromAI(ai("LOW", "BUG", "src/Invented.java", 1), CHUNK, FILES)).isEmpty();
        assertThat(Findings.fromAI(new AIReviewFinding("LOW", "BUG", " ", "d", "r", "src/App.java", 1), CHUNK, FILES))
                .isEmpty();
    }

    @Test
    void aRuleFindingWinsOverTheAISayingTheSameAndTheResultIsSortedBySeverity() {
        DiffFile dao = DiffFile.parse("Dao.java", "added", "@@ -0,0 +1 @@\n+String password = \"admin123\";");
        ReviewFinding rule = Findings.fromRule(SecurityRules.scan(List.of(dao)).getFirst());
        ReviewFinding aiSameLine = finding("Dao.java", 1, Severity.HIGH, "Hardcoded password");
        ReviewFinding aiOther = finding("Dao.java", null, Severity.CRITICAL, "No tests");
        ReviewFinding aiDuplicate = finding("Dao.java", null, Severity.CRITICAL, "no TESTS");

        List<ReviewFinding> merged = Findings.merge(List.of(rule), List.of(aiSameLine, aiOther, aiDuplicate));

        assertThat(merged).extracting(ReviewFinding::getTitle).containsExactly("No tests", "Hard-coded credential");
        assertThat(merged.get(1).getRuleId()).isEqualTo("hardcoded-credential");
    }

    @Test
    void theMockIsFreeAndRealTokensCostTheirPrice() {
        AIProperties.Pricing pricing = new AIProperties.Pricing(new BigDecimal("0.15"), new BigDecimal("0.60"));

        assertThat(ReviewCost.estimate("mock", pricing, 1_000_000, 1_000_000)).isEqualByComparingTo("0");
        // 10,000 in × $0.15/M + 2,000 out × $0.60/M = 0.0015 + 0.0012
        assertThat(ReviewCost.estimate("openai", pricing, 10_000, 2_000)).isEqualByComparingTo("0.0027");
    }

    private static AIReviewFinding ai(String severity, String category, String file, Integer line) {
        return new AIReviewFinding(severity, category, "Title", "Description", "Fix it", file, line);
    }

    private static ReviewFinding finding(String path, Integer line, Severity severity, String title) {
        ReviewFinding finding = new ReviewFinding();
        finding.setSource(FindingSource.AI);
        finding.setFilePath(path);
        finding.setLine(line);
        finding.setSeverity(severity);
        finding.setCategory(FindingCategory.BUG);
        finding.setTitle(title);
        return finding;
    }
}
