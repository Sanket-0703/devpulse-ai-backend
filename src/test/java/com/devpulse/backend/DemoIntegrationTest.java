package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.DemoStatusResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.dto.review.ReviewDetailResponse;
import com.devpulse.backend.dto.review.ReviewFindingResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.entity.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Level 7 demo mode (on in tests too): "Try the demo" signs in to a seeded
 * account whose sample pull request was reviewed by the real pipeline,
 * without GitHub or an AI key.
 */
class DemoIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theDemoAccountHasARepositoryAPullRequestAndAFinishedReview() {

        assertThat(rest.getForObject("/api/auth/demo", DemoStatusResponse.class).enabled()).isTrue();

        ResponseEntity<AuthResponse> signIn = rest.postForEntity("/api/auth/demo", null, AuthResponse.class);
        assertThat(signIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signIn.getHeaders().get(HttpHeaders.SET_COOKIE)).anyMatch(c -> c.startsWith("devpulse_refresh="));
        String token = signIn.getBody().accessToken();
        assertThat(signIn.getBody().user().email()).isEqualTo("demo@devpulse.local");

        RepositoryResponse repository = rest.exchange("/api/github/repositories", HttpMethod.GET, bearer(token),
                        new ParameterizedTypeReference<GitHubAppIntegrationTest.Page<RepositoryResponse>>() {
                        })
                .getBody().content().getFirst();
        assertThat(repository.fullName()).isEqualTo("devpulse-demo/shop-api");

        // It looks installed, but never talks to GitHub.
        for (String sync : List.of("/api/github/repositories/sync",
                "/api/github/repositories/" + repository.id() + "/pull-requests/sync")) {
            ResponseEntity<Map> refused = rest.exchange(sync, HttpMethod.POST, bearer(token),
                    Map.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat((String) refused.getBody().get("message")).contains("demo account isn't connected");
        }

        // The diff comes from the seeded cache: no GitHub involved.
        PullRequestFilesResponse files = rest.exchange("/api/github/repositories/" + repository.id()
                + "/pull-requests/12/files", HttpMethod.GET, bearer(token), PullRequestFilesResponse.class).getBody();
        assertThat(files.files()).hasSize(6);

        // The startup review finishes in the background.
        ReviewResponse review = await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> reviews(token, repository.id()), list -> !list.isEmpty()
                        && list.getFirst().status() == ReviewStatus.SUCCEEDED).getFirst();
        assertThat(review.filesReviewed()).isEqualTo(4);
        assertThat(review.filesSkipped()).isEqualTo(2); // the lock file and the image

        ReviewDetailResponse detail = rest.exchange("/api/reviews/" + review.id(), HttpMethod.GET, bearer(token),
                ReviewDetailResponse.class).getBody();
        assertThat(detail.findings()).extracting(ReviewFindingResponse::ruleId)
                .contains("hardcoded-credential", "sql-concatenation", "weak-hash", "raw-html");
        assertThat(detail.findings()).extracting(ReviewFindingResponse::title)
                .contains("Exception swallowed", "Debug output left in", "Blocking sleep", "Unfinished work");
    }

    private List<ReviewResponse> reviews(String token, Long repositoryId) {
        return rest.exchange("/api/github/repositories/" + repositoryId + "/pull-requests/12/reviews",
                HttpMethod.GET, bearer(token), new ParameterizedTypeReference<List<ReviewResponse>>() {
                }).getBody();
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
