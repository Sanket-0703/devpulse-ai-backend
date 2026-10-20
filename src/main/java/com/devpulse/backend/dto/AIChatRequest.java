package com.devpulse.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record AIChatRequest(

        @NotBlank(message = "Message cannot be blank")
        String message

) {
}