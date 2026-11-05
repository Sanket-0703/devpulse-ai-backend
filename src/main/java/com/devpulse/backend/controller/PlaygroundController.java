package com.devpulse.backend.controller;

import com.devpulse.backend.dto.playground.PlaygroundRequest;
import com.devpulse.backend.dto.playground.PlaygroundResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.PlaygroundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/playground")
@Tag(name = "Playground", description = "Paste code and get an instant review")
public class PlaygroundController {

    private final PlaygroundService playgroundService;

    public PlaygroundController(PlaygroundService playgroundService) {
        this.playgroundService = playgroundService;
    }

    @PostMapping("/review")
    @Operation(summary = "Review a pasted snippet",
            description = "Security rules + the AI model, like a pull request review. The code isn't stored. "
                    + "400 for an empty or over-long snippet; 429 over the per-minute or daily limits.")
    public PlaygroundResponse review(@AuthenticationPrincipal CustomUserPrincipal principal,
                                     @Valid @RequestBody PlaygroundRequest request) {
        return playgroundService.review(principal.getId(), request);
    }
}
