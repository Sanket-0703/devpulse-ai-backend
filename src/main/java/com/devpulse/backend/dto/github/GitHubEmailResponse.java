package com.devpulse.backend.dto.github;

/** One entry of GET /user/emails (needs the app's "Email addresses: read" permission). */
public record GitHubEmailResponse(String email, boolean primary, boolean verified) {
}
