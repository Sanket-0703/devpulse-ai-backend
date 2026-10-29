package com.devpulse.backend.dto.review;

import com.devpulse.backend.entity.FindingCategory;
import com.devpulse.backend.entity.FindingSource;
import com.devpulse.backend.entity.ReviewFinding;
import com.devpulse.backend.entity.Severity;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ReviewFindingResponse(
        @Schema(requiredMode = REQUIRED) Long id,
        @Schema(requiredMode = REQUIRED, description = "AI model or DevPulse security rule") FindingSource source,
        @Schema(description = "The security rule that matched (RULE findings)") String ruleId,
        @Schema(requiredMode = REQUIRED) Severity severity,
        @Schema(requiredMode = REQUIRED) FindingCategory category,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) String description,
        String recommendation,
        @Schema(requiredMode = REQUIRED) String filePath,
        @Schema(description = "Line in the new version of the file; null for the whole file") Integer line,
        @Schema(requiredMode = REQUIRED) boolean dismissed
) {

    public static ReviewFindingResponse from(ReviewFinding finding) {
        return new ReviewFindingResponse(finding.getId(), finding.getSource(), finding.getRuleId(),
                finding.getSeverity(), finding.getCategory(), finding.getTitle(), finding.getDescription(),
                finding.getRecommendation(), finding.getFilePath(), finding.getLine(), finding.isDismissed());
    }
}
