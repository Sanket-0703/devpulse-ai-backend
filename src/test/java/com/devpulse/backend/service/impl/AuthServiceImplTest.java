package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.ChangePasswordRequest;
import com.devpulse.backend.dto.LoginRequest;
import com.devpulse.backend.dto.RegisterRequest;
import com.devpulse.backend.dto.UserResponse;
import com.devpulse.backend.entity.Role;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.EmailAlreadyExistsException;
import com.devpulse.backend.exception.FieldValidationException;
import com.devpulse.backend.exception.InvalidCredentialsException;
import com.devpulse.backend.exception.InvalidRefreshTokenException;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.security.InvalidTokenException;
import com.devpulse.backend.security.JwtService;
import com.devpulse.backend.service.AuthService.AuthResult;
import com.devpulse.backend.service.JwtBlacklistService;
import com.devpulse.backend.service.RefreshTokenService;
import com.devpulse.backend.service.RefreshTokenService.IssuedRefreshToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final String DUMMY_HASH = "dummy-hash";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private JwtBlacklistService jwtBlacklistService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode("not-a-real-password")).thenReturn(DUMMY_HASH);

        authService = new AuthServiceImpl(
                userRepository, passwordEncoder, jwtService, refreshTokenService,
                jwtBlacklistService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registerNormalizesTheEmailAndHashesThePassword() {

        when(userRepository.existsByEmailIgnoreCase("ada@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd!23")).thenReturn("bcrypt-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        UserResponse response = authService.register(registerRequest("  Ada Lovelace ", " Ada@Example.COM ", "Passw0rd!23"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());

        assertThat(saved.getValue().getEmail()).isEqualTo("ada@example.com");
        assertThat(saved.getValue().getName()).isEqualTo("Ada Lovelace");
        assertThat(saved.getValue().getPassword()).isEqualTo("bcrypt-hash");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Ada Lovelace");
        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.role()).isEqualTo(Role.ROLE_USER);
    }

    @Test
    void registerRejectsAnEmailThatAlreadyExists() {

        when(userRepository.existsByEmailIgnoreCase("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest("Ada", "ADA@example.com", "Passw0rd!23")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void loginIssuesAccessAndRefreshTokens() {

        User user = user();
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Passw0rd!23", "bcrypt-hash")).thenReturn(true);
        IssuedRefreshToken refresh = new IssuedRefreshToken(5L, "raw-refresh", NOW.plus(Duration.ofDays(7)));
        when(refreshTokenService.issue(user)).thenReturn(refresh);
        when(jwtService.issue(user)).thenReturn(new JwtService.IssuedAccessToken("jwt", NOW.plusSeconds(900)));
        when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        AuthResult result = authService.login(loginRequest("Ada@Example.com", "Passw0rd!23"));

        assertThat(result.response().accessToken()).isEqualTo("jwt");
        assertThat(result.response().tokenType()).isEqualTo("Bearer");
        assertThat(result.response().expiresIn()).isEqualTo(900);
        assertThat(result.response().user().email()).isEqualTo("ada@example.com");
        assertThat(result.refreshToken()).isEqualTo(refresh);
    }

    @Test
    void loginWithWrongPasswordFails() {

        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("wrong", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("ada@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void loginWithUnknownEmailStillChecksAPasswordHash() {

        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        // Same bcrypt work as a real account, so response time doesn't reveal which emails exist.
        verify(passwordEncoder).matches("whatever", DUMMY_HASH);
    }

    @Test
    void refreshWithoutACookieFails() {

        assertThatThrownBy(() -> authService.refresh(null)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> authService.refresh(" ")).isInstanceOf(InvalidRefreshTokenException.class);

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void logoutRevokesTheRefreshTokenAndBlocksTheAccessTokenUntilItExpires() {

        when(jwtService.parse("jwt")).thenReturn(
                new JwtService.AccessTokenClaims("jti-1", 1L, NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofMinutes(10))));

        authService.logout("raw-refresh", "jwt");

        verify(refreshTokenService).revokeFamily("raw-refresh");
        verify(jwtBlacklistService).blacklist("jti-1", Duration.ofMinutes(10));
    }

    @Test
    void logoutIgnoresAnInvalidAccessToken() {

        when(jwtService.parse("expired")).thenThrow(new InvalidTokenException("Access token has expired"));

        authService.logout(null, "expired");

        verifyNoInteractions(jwtBlacklistService, refreshTokenService);
    }

    @Test
    void changePasswordRejectsAWrongCurrentPassword() {

        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("wrong", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(1L, changePassword("wrong", "NewPassw0rd!")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("currentPassword"));

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void changePasswordRejectsReusingTheCurrentPassword() {

        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("Passw0rd!23", "bcrypt-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword(1L, changePassword("Passw0rd!23", "Passw0rd!23")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("newPassword"));
    }

    @Test
    void changePasswordEndsOtherSessionsAndStartsANewOne() {

        User user = user();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Passw0rd!23", "bcrypt-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewPassw0rd!", "bcrypt-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPassw0rd!")).thenReturn("new-hash");
        IssuedRefreshToken refresh = new IssuedRefreshToken(9L, "raw", NOW.plus(Duration.ofDays(7)));
        when(refreshTokenService.issue(user)).thenReturn(refresh);
        when(jwtService.issue(user)).thenReturn(new JwtService.IssuedAccessToken("jwt", NOW.plusSeconds(900)));
        when(jwtService.getAccessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        AuthResult result = authService.changePassword(1L, changePassword("Passw0rd!23", "NewPassw0rd!"));

        assertThat(user.getPassword()).isEqualTo("new-hash");
        assertThat(user.getTokensValidAfter()).isEqualTo(NOW);
        verify(refreshTokenService).revokeAll(1L);
        assertThat(result.refreshToken()).isEqualTo(refresh);
        assertThat(result.response().accessToken()).isEqualTo("jwt");
    }

    @Test
    void logoutEverywhereSetsTheCutoffToWholeSeconds() {

        // The clock has milliseconds; JWT "iat" doesn't, so the cutoff mustn't either.
        AuthServiceImpl service = new AuthServiceImpl(userRepository, passwordEncoder, jwtService,
                refreshTokenService, jwtBlacklistService,
                Clock.fixed(NOW.plusMillis(750), ZoneOffset.UTC));
        User user = user();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.logoutEverywhere(1L);

        assertThat(user.getTokensValidAfter()).isEqualTo(NOW);
        verify(refreshTokenService).revokeAll(1L);
    }

    private static ChangePasswordRequest changePassword(String current, String next) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword(current);
        request.setNewPassword(next);
        return request;
    }

    private static User user() {
        User user = new User();
        user.setId(1L);
        user.setName("Ada");
        user.setEmail("ada@example.com");
        user.setPassword("bcrypt-hash");
        user.setRole(Role.ROLE_USER);
        return user;
    }

    private static RegisterRequest registerRequest(String name, String email, String password) {
        RegisterRequest request = new RegisterRequest();
        request.setName(name);
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }

    private static LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }
}
