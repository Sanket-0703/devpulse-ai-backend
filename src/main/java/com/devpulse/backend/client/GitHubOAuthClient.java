package com.devpulse.backend.client;

import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.dto.github.GitHubTokenResponse;
import com.devpulse.backend.exception.GitHubIntegrationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Map;

/**
 * The OAuth side of the DevPulse GitHub App: the URLs the browser is sent
 * to, and the server-to-GitHub calls that turn codes into tokens, refresh
 * them, and revoke them. All authenticated with the app's client id + secret.
 */
@Component
public class GitHubOAuthClient {

    private final GitHubProperties properties;
    private final RestClient restClient;

    public GitHubOAuthClient(GitHubProperties properties) {
        this.properties = properties;

        // Never wait forever on GitHub: a hung request would tie up a server thread.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** GitHub's "Authorize DevPulse?" page. GitHub Apps ignore scopes: permissions come from the app settings. */
    public String authorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(properties.webUrl())
                .path("/login/oauth/authorize")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("state", state)
                .encode()
                .toUriString();
    }

    /** GitHub's "Install DevPulse on which repositories?" page. */
    public String installUrl(String state) {
        return UriComponentsBuilder.fromUriString(properties.webUrl())
                .path("/apps/{slug}/installations/new")
                .queryParam("state", state)
                .encode()
                .buildAndExpand(properties.appSlug())
                .toUriString();
    }

    /** Where the user manages which repositories an installation can see. */
    public String installationSettingsUrl(long installationId) {
        return properties.webUrl() + "/settings/installations/" + installationId;
    }

    /** Exchanges the one-time {@code code} from the callback for a user token. */
    public GitHubTokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());
        return requestToken(form);
    }

    /** Trades a refresh token for a new access token (and a new refresh token). */
    public GitHubTokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken(form);
    }

    /**
     * Revokes the user's authorization of DevPulse entirely (all its tokens).
     * Afterwards GitHub asks the user to authorize again on the next connect.
     */
    public void revokeGrant(String accessToken) {
        try {
            restClient.method(HttpMethod.DELETE)
                    .uri(properties.apiUrl() + "/applications/{clientId}/grant", properties.clientId())
                    .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("access_token", accessToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new GitHubIntegrationException("github_error", "Could not revoke the GitHub authorization", e);
        }
    }

    private GitHubTokenResponse requestToken(MultiValueMap<String, String> form) {
        GitHubTokenResponse response;
        try {
            response = restClient.post()
                    .uri(properties.webUrl() + "/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GitHubTokenResponse.class);
        } catch (RestClientException e) {
            throw new GitHubIntegrationException("github_error", "GitHub token request failed", e);
        }

        // GitHub reports errors with HTTP 200 + an "error" field.
        if (response == null || response.getError() != null || response.getAccessToken() == null) {
            String error = response == null ? "empty response" : response.getError();
            throw new GitHubIntegrationException("github_error", "GitHub rejected the token request: " + error);
        }
        return response;
    }
}
