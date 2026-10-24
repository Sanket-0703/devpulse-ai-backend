package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.client.GitHubOAuthClient;
import com.devpulse.backend.common.EmailAddresses;
import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.dto.github.GitHubConnectionResponse;
import com.devpulse.backend.dto.github.GitHubEmailResponse;
import com.devpulse.backend.dto.github.GitHubInstallationsResponse;
import com.devpulse.backend.dto.github.GitHubTokenResponse;
import com.devpulse.backend.dto.github.GitHubUserResponse;
import com.devpulse.backend.entity.GitHubAccount;
import com.devpulse.backend.entity.Role;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.GitHubAccountRepository;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.GitHubConnectionService;
import com.devpulse.backend.service.GitHubOAuthStateService;
import com.devpulse.backend.service.GitHubOAuthStateService.Flow;
import com.devpulse.backend.service.GitHubTokenService;
import com.devpulse.backend.service.RefreshTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static com.devpulse.backend.service.GitHubConnectionService.CallbackResult.Connected;
import static com.devpulse.backend.service.GitHubConnectionService.CallbackResult.Failed;
import static com.devpulse.backend.service.GitHubConnectionService.CallbackResult.SignedIn;

@Slf4j
@Service
public class GitHubConnectionServiceImpl implements GitHubConnectionService {

    private final GitHubProperties properties;
    private final GitHubOAuthClient oauthClient;
    private final GitHubApiClient apiClient;
    private final GitHubTokenService tokenService;
    private final GitHubOAuthStateService stateService;
    private final GitHubAccountRepository githubAccountRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public GitHubConnectionServiceImpl(
            GitHubProperties properties,
            GitHubOAuthClient oauthClient,
            GitHubApiClient apiClient,
            GitHubTokenService tokenService,
            GitHubOAuthStateService stateService,
            GitHubAccountRepository githubAccountRepository,
            UserRepository userRepository,
            RefreshTokenService refreshTokenService,
            PlatformTransactionManager transactionManager,
            Clock clock) {

        this.properties = properties;
        this.oauthClient = oauthClient;
        this.apiClient = apiClient;
        this.tokenService = tokenService;
        this.stateService = stateService;
        this.githubAccountRepository = githubAccountRepository;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------- starting a flow ----------

    @Override
    public String signInUrl() {
        return oauthClient.authorizeUrl(stateService.create(Flow.SIGN_IN, null));
    }

    @Override
    public String connectUrl(Long userId) {
        return oauthClient.authorizeUrl(stateService.create(Flow.CONNECT, userId));
    }

    @Override
    public String installUrl(Long userId) {
        // "Request user authorization during installation" is on, so after
        // installing, GitHub also authorizes and calls back with a code.
        return oauthClient.installUrl(stateService.create(Flow.CONNECT, userId));
    }

    // ---------- the callback ----------

    @Override
    public CallbackResult handleCallback(CallbackParams params) {
        try {
            return processCallback(params);
        } catch (GitHubIntegrationException e) {
            log.warn("GitHub callback failed: {}", e.getMessage());
            return new Failed(e.getReason());
        } catch (RuntimeException e) {
            log.error("Unexpected error in GitHub callback", e);
            return new Failed("github_error");
        }
    }

    private CallbackResult processCallback(CallbackParams params) {

        // The user clicked "Cancel" on GitHub, or GitHub reported a problem.
        if (params.error() != null) {
            return new Failed("access_denied".equals(params.error()) ? "access_denied" : "github_error");
        }
        if (params.code() == null || params.code().isBlank()) {
            return new Failed("github_error");
        }

        Optional<GitHubOAuthStateService.State> state = stateService.consume(params.state());

        // No valid state. GitHub can still send users here on its own after
        // they install the app or change its repositories ("Redirect on
        // update"). That's allowed to refresh an existing link, never to
        // create one or sign anyone in (that would enable login CSRF).
        if (state.isEmpty()) {
            if (params.setupAction() == null) {
                return new Failed("invalid_state");
            }
            GitHubIdentity identity = fetchIdentity(params.code());
            return transaction.execute(tx -> refreshExistingLink(identity));
        }

        // HTTP calls to GitHub happen outside any database transaction, so a
        // slow GitHub never holds a database connection.
        GitHubIdentity identity = fetchIdentity(params.code());

        return switch (state.get().flow()) {
            case SIGN_IN -> signIn(identity);
            case CONNECT -> transaction.execute(tx -> connect(state.get().userId(), identity));
        };
    }

    /** Everything we need from GitHub about the person who just authorized. */
    private record GitHubIdentity(GitHubTokenResponse tokens, GitHubUserResponse user, Long installationId,
                                  String accessToken) {
    }

    private GitHubIdentity fetchIdentity(String code) {
        GitHubTokenResponse tokens = oauthClient.exchangeCode(code);
        String accessToken = tokens.getAccessToken();
        GitHubUserResponse user = apiClient.getAuthenticatedUser(accessToken);
        return new GitHubIdentity(tokens, user, findInstallationId(accessToken), accessToken);
    }

    /** Our app's installation the user can access, if any (filtering by app id). */
    private Long findInstallationId(String accessToken) {
        GitHubInstallationsResponse response = apiClient.getUserInstallations(accessToken);
        if (response == null || response.installations() == null || properties.appId() == null) {
            return null;
        }
        return response.installations().stream()
                .filter(installation -> installation.appId() == properties.appId())
                .map(GitHubInstallationsResponse.Installation::id)
                .findFirst()
                .orElse(null);
    }

    private CallbackResult signIn(GitHubIdentity identity) {

        // Already linked: sign in as that DevPulse user.
        Optional<SignedIn> existing = transaction.execute(tx ->
                githubAccountRepository.findByGithubId(identity.user().getId()).map(account -> {
                    applyIdentity(account, identity);
                    return new SignedIn(refreshTokenService.issue(account.getUser()), false);
                }));
        if (existing.isPresent()) {
            return existing.get();
        }

        // New to DevPulse: we need a verified email for the account.
        Optional<String> email = primaryVerifiedEmail(identity.accessToken());
        if (email.isEmpty()) {
            return new Failed("no_verified_email");
        }

        return transaction.execute(tx -> {
            // Deliberately NOT linking to an existing account with the same
            // email: DevPulse doesn't verify emails, so someone could have
            // registered your address first and be waiting for you to sign
            // in with GitHub into *their* account (pre-account takeover).
            if (userRepository.existsByEmailIgnoreCase(email.get())) {
                return new Failed("email_exists");
            }

            User user = new User();
            user.setName(displayName(identity.user()));
            user.setEmail(email.get());
            user.setRole(Role.ROLE_USER);
            user.setPassword(null); // can be set later in Settings → Security
            userRepository.save(user);

            GitHubAccount account = new GitHubAccount();
            applyIdentity(account, identity);
            user.connectGitHub(account);
            githubAccountRepository.save(account);

            return new SignedIn(refreshTokenService.issue(user), true);
        });
    }

    private CallbackResult connect(Long userId, GitHubIdentity identity) {

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return new Failed("invalid_state");
        }

        Optional<GitHubAccount> linked = githubAccountRepository.findByGithubId(identity.user().getId());
        if (linked.isPresent() && !linked.get().getUser().getId().equals(userId)) {
            return new Failed("github_account_in_use");
        }

        GitHubAccount current = user.getGithubAccount();
        if (current != null && !current.getGithubId().equals(identity.user().getId())) {
            return new Failed("already_connected");
        }

        GitHubAccount account = current != null ? current : new GitHubAccount();
        applyIdentity(account, identity);
        if (current == null) {
            user.connectGitHub(account);
        }
        githubAccountRepository.save(account);
        return new Connected();
    }

    private CallbackResult refreshExistingLink(GitHubIdentity identity) {
        return githubAccountRepository.findByGithubId(identity.user().getId())
                .<CallbackResult>map(account -> {
                    applyIdentity(account, identity);
                    return new Connected();
                })
                .orElseGet(() -> new Failed("not_connected"));
    }

    /** Copies the latest GitHub profile, tokens and installation onto the account. */
    private void applyIdentity(GitHubAccount account, GitHubIdentity identity) {
        Instant now = clock.instant();
        account.setGithubId(identity.user().getId());
        account.setUsername(identity.user().getLogin());
        account.setAvatarUrl(identity.user().getAvatarUrl());
        account.setInstallationId(identity.installationId());
        if (account.getConnectedAt() == null) {
            account.setConnectedAt(now);
        }
        GitHubTokenServiceImpl.applyTokens(account, identity.tokens(), now);
    }

    private Optional<String> primaryVerifiedEmail(String accessToken) {
        return apiClient.getEmails(accessToken).stream()
                .filter(GitHubEmailResponse::verified)
                .sorted((a, b) -> Boolean.compare(b.primary(), a.primary())) // primary first
                .map(e -> EmailAddresses.normalize(e.email()))
                .findFirst();
    }

    private static String displayName(GitHubUserResponse user) {
        String name = user.getName() == null || user.getName().isBlank() ? user.getLogin() : user.getName().trim();
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    // ---------- status and disconnect ----------

    @Override
    @Transactional(readOnly = true)
    public GitHubConnectionResponse status(Long userId) {

        User user = findUser(userId);
        GitHubAccount account = user.getGithubAccount();
        if (account == null) {
            return GitHubConnectionResponse.notConnected();
        }

        Long installationId = account.getInstallationId();
        return new GitHubConnectionResponse(
                true,
                account.getUsername(),
                account.getAvatarUrl(),
                properties.webUrl() + "/" + account.getUsername(),
                account.getConnectedAt(),
                installationId != null,
                installationId == null ? null : oauthClient.installationSettingsUrl(installationId));
    }

    @Override
    @Transactional
    public void disconnect(Long userId) {

        User user = findUser(userId);
        GitHubAccount account = user.getGithubAccount();
        if (account == null) {
            return; // already disconnected: nothing to do
        }
        if (user.getPassword() == null) {
            throw new ConflictException(
                    "Set a password in Settings → Security before disconnecting GitHub, "
                            + "otherwise you won't be able to sign in.");
        }

        // Best effort: if GitHub is down or the token is long dead, still
        // remove the link here (the user can revoke on GitHub themselves).
        try {
            oauthClient.revokeGrant(tokenService.getValidAccessToken(account));
        } catch (GitHubIntegrationException e) {
            log.warn("Could not revoke GitHub authorization for user {}: {}", userId, e.getMessage());
        }

        user.disconnectGitHub();
        githubAccountRepository.delete(account);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
