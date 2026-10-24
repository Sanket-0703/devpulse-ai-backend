package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.JwtProperties;
import com.devpulse.backend.entity.RefreshToken;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.InvalidRefreshTokenException;
import com.devpulse.backend.repository.RefreshTokenRepository;
import com.devpulse.backend.service.RefreshTokenService.IssuedRefreshToken;
import com.devpulse.backend.service.RefreshTokenService.Rotation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    @Mock
    private RefreshTokenRepository repository;

    private RefreshTokenServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {

        JwtProperties properties = new JwtProperties();
        properties.setRefreshTokenTtl(Duration.ofDays(7));

        service = new RefreshTokenServiceImpl(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        user = new User();
        user.setId(7L);
    }

    @Test
    void issueStoresOnlyTheHashOfARandomToken() {

        givenSaveAssignsId(100L);

        IssuedRefreshToken issued = service.issue(user);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());

        assertThat(issued.value()).hasSizeGreaterThanOrEqualTo(43); // 32 bytes, base64url
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(RefreshTokenServiceImpl.hash(issued.value()))
                .isNotEqualTo(issued.value());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(saved.getValue().getFamilyId()).isNotNull();
    }

    @Test
    void rotateRevokesTheOldTokenAndIssuesOneInTheSameFamily() {

        RefreshToken current = token(1L, null, NOW.plus(Duration.ofDays(1)));
        when(repository.findByTokenHashForUpdate(RefreshTokenServiceImpl.hash("old"))).thenReturn(Optional.of(current));
        givenSaveAssignsId(2L);

        Rotation rotation = service.rotate("old");

        assertThat(rotation.user()).isSameAs(user);
        assertThat(rotation.refreshToken().id()).isEqualTo(2L);
        assertThat(current.getRevokedAt()).isEqualTo(NOW);
        assertThat(current.getReplacedById()).isEqualTo(2L);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getFamilyId()).isEqualTo(current.getFamilyId());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {

        // Rotated an hour ago, presented again now: someone kept a copy.
        RefreshToken stolen = token(1L, NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(1)));
        stolen.setReplacedById(2L);
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(stolen));

        assertThatThrownBy(() -> service.rotate("stolen"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Refresh token has already been used");

        verify(repository).revokeFamily(stolen.getFamilyId(), NOW);
        verify(repository, never()).save(any());
    }

    @Test
    void reuseRightAfterRotationIsTreatedAsAConcurrentRefresh() {

        // Another tab rotated this token 5 seconds ago.
        RefreshToken justRotated = token(1L, NOW.minusSeconds(5), NOW.plus(Duration.ofDays(1)));
        justRotated.setReplacedById(2L);
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(justRotated));

        assertThatThrownBy(() -> service.rotate("old"))
                .isInstanceOfSatisfying(InvalidRefreshTokenException.class,
                        e -> assertThat(e.shouldClearCookie()).isFalse());

        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    void tokenRevokedByLogoutCannotBeUsed() {

        RefreshToken loggedOut = token(1L, NOW.minusSeconds(5), NOW.plus(Duration.ofDays(1)));
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(loggedOut));

        assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(InvalidRefreshTokenException.class);

        // No replacement → not a concurrent refresh → treat as reuse.
        verify(repository).revokeFamily(loggedOut.getFamilyId(), NOW);
    }

    @Test
    void expiredTokenIsRejected() {

        RefreshToken expired = token(1L, null, NOW.minusSeconds(1));
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate("old"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Session has expired, please sign in again");

        verify(repository, never()).save(any());
    }

    @Test
    void unknownTokenIsRejected() {

        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("made-up"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Refresh token is invalid");
    }

    @Test
    void revokeFamilyIgnoresUnknownTokens() {

        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.revokeFamily("made-up");

        verify(repository, never()).revokeFamily(any(), any());
    }

    private RefreshToken token(Long id, Instant revokedAt, Instant expiresAt) {

        RefreshToken token = new RefreshToken();
        token.setId(id);
        token.setUser(user);
        token.setFamilyId(UUID.randomUUID());
        token.setTokenHash("hash-" + id);
        token.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        return token;
    }

    private void givenSaveAssignsId(Long id) {
        when(repository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken token = invocation.getArgument(0);
            token.setId(id);
            return token;
        });
    }
}
