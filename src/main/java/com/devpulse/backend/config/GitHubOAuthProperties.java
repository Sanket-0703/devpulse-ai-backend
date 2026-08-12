package com.devpulse.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "github.oauth")
public class GitHubOAuthProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String stateSecret;
}