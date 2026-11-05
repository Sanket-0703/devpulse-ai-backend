package com.devpulse.backend;

import com.devpulse.backend.dto.dashboard.DashboardResponse;
import com.devpulse.backend.dto.review.ReviewDetailResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.entity.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Level 8: the dashboard summary, from a brand-new account to a reviewed pull request. */
class DashboardIntegrationTest extends GitHubAppIntegrationTest {

    private static final String FILES = """
            [{"filename":"src/Dao.java","status":"added","additions":2,"deletions":0,"changes":2,
              "patch":"@@ -0,0 +1,2 @@\\n+String password = \\"admin123\\";\\n+System.out.println(password);"}]
            """;

    @Test
    void aNewAccountSeesTheFirstStepsOnly() {
        DashboardResponse dashboard = dashboard(registerAndLogin());

        assertThat(dashboard.onboarding()).isEqualTo(new DashboardResponse.Onboarding(false, false, false, false,
                false));
        assertThat(dashboard.stats()).isEqualTo(new DashboardResponse.Stats(0, 0, 0, 0, 0));
        assertThat(dashboard.recentReviews()).isEmpty();
        assertThat(dashboard.usage().reviewsToday()).isZero();
    }

    @Test
    void afterAReviewTheDashboardShowsTheCountsAndTheLatestFindings() throws Exception {
        Setup ada = signedInWithOneRepo();
        stubPulls(ada, 1,
                pr(2, "Add DAO", "open", null, "2026-10-25T10:00:00Z", "ada", false),
                pr(1, "Init", "closed", "2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z", "ada", false));
        rest.exchange("/api/github/repositories/" + ada.repositoryId() + "/pull-requests/sync", HttpMethod.POST,
                bearer(ada.token()), Map.class);
        rest.exchange("/api/github/repositories/" + ada.repositoryId(), HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(Map.of("reviewEnabled", true), bearerHeaders(ada.token())),
                Map.class);
        stubFiles(ada, 2, FILES);

        // Reviewed twice: only the latest review's findings count.
        awaitReview(ada, startReview(ada));
        awaitReview(ada, startReview(ada));

        DashboardResponse dashboard = dashboard(ada.token());

        assertThat(dashboard.onboarding()).isEqualTo(new DashboardResponse.Onboarding(true, true, true, true, true));
        assertThat(dashboard.stats()).isEqualTo(new DashboardResponse.Stats(1, 1, 1, 2, 2));
        assertThat(dashboard.openFindings().high()).isEqualTo(1); // hard-coded credential (rule)
        assertThat(dashboard.openFindings().low()).isEqualTo(1);  // debug output (mock AI)
        assertThat(dashboard.recentReviews()).hasSize(2).allSatisfy(r -> assertThat(r.pullRequestNumber()).isEqualTo(2));
        assertThat(dashboard.usage().reviewsToday()).isEqualTo(2);
    }

    private DashboardResponse dashboard(String token) {
        return rest.exchange("/api/dashboard", HttpMethod.GET, bearer(token), DashboardResponse.class).getBody();
    }

    private Long startReview(Setup setup) {
        return rest.exchange("/api/github/repositories/" + setup.repositoryId() + "/pull-requests/2/reviews",
                HttpMethod.POST, bearer(setup.token()), ReviewResponse.class).getBody().id();
    }

    private void awaitReview(Setup setup, Long reviewId) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> rest.exchange("/api/reviews/" + reviewId, HttpMethod.GET, bearer(setup.token()),
                        ReviewDetailResponse.class).getBody().review().status() == ReviewStatus.SUCCEEDED);
    }
}
