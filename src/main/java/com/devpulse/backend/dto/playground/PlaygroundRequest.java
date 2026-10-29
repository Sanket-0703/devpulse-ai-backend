package com.devpulse.backend.dto.playground;

import com.devpulse.backend.entity.PlaygroundLanguage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Code pasted into the Playground. */
public record PlaygroundRequest(
        @Schema(requiredMode = REQUIRED)
        @NotNull(message = "Choose a language")
        PlaygroundLanguage language,

        @Schema(requiredMode = REQUIRED, maxLength = PlaygroundRequest.MAX_CODE_CHARS)
        @NotBlank(message = "Paste some code to review")
        @Size(max = PlaygroundRequest.MAX_CODE_CHARS, message = "Code must be at most 20,000 characters")
        String code
) {

    /** About 5,000 tokens: two AI requests at most. */
    public static final int MAX_CODE_CHARS = 20_000;
}
