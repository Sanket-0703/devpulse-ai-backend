package com.devpulse.backend.dto.playground;

import com.devpulse.backend.entity.FindingCategory;
import com.devpulse.backend.entity.FindingSource;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.entity.Severity;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A finding on the pasted snippet (not stored, so no id). */
public record PlaygroundFinding(
        @Schema(requiredMode = REQUIRED) FindingSource source,
        String ruleId,
        @Schema(requiredMode = REQUIRED) Severity severity,
        @Schema(requiredMode = REQUIRED) FindingCategory category,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) String description,
        String recommendation,
        @Schema(description = "Line of the snippet (1-based); null for the whole snippet") Integer line
) {

    public static PlaygroundFinding from(ReviewFinding finding) {
        return new PlaygroundFinding(finding.getSource(), finding.getRuleId(), finding.getSeverity(),
                finding.getCategory(), finding.getTitle(), finding.getDescription(), finding.getRecommendation(),
                finding.getLine());
    }
}
