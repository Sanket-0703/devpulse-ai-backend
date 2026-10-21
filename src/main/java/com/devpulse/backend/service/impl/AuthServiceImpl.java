package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.LoginRequest;
import com.devpulse.backend.dto.RegisterRequest;
import com.devpulse.backend.dto.UserResponse;
import com.devpulse.backend.entity.Role;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.EmailAlreadyExistsException;
import com.devpulse.backend.exception.InvalidCredentialsException;
import com.devpulse.backend.exception.InvalidRefreshTokenException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.security.InvalidTokenException;
import com.devpulse.backend.security.JwtService;
import com.devpulse.backend.service.AuthService;
import com.devpulse.backend.service.JwtBlacklistService;
import com.devpulse.backend.service.RefreshTokenService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JwtBlacklistService jwtBlacklistService;
    private final Clock clock;

    // Compared against when the email is unknown, so a login for a missing
    // account takes as long as one with a wrong password (no user enumeration
    // by timing).
    private final String dummyPasswordHash;

    public AuthServiceImpl(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            JwtBlacklistService jwtBlacklistService,
            Clock clock) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.jwtBlacklistService = jwtBlacklistService;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("not-a-real-password");
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {

        String email = normalizeEmail(request.getEmail());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyExistsException("An account with this email already exists");
        }

        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(email);
        user.setRole(Role.ROLE_USER);
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups with the same email at the same moment: the unique
            // constraint catches the one the check above missed.
            throw new EmailAlreadyExistsException("An account with this email already exists");
        }
    }

    @Override
    @Transactional
    public AuthResult login(LoginRequest request) {

        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.getEmail()))
                .orElse(null);

        String hashToCheck = user != null ? user.getPassword() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS);
        }

        return issueTokens(user, refreshTokenService.issue(user));
    }

    @Override
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResult refresh(String rawRefreshToken) {

        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException("Not signed in");
        }

        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken);

        return issueTokens(rotation.user(), rotation.refreshToken());
    }

    @Override
    @Transactional
    public void logout(String rawRefreshToken, String accessToken) {

        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revokeFamily(rawRefreshToken);
        }

        if (accessToken != null && !accessToken.isBlank()) {
            try {
                JwtService.AccessTokenClaims claims = jwtService.parse(accessToken);
                Duration remaining = Duration.between(clock.instant(), claims.expiresAt());

                // Block the access token until it would have expired anyway.
                if (!remaining.isNegative() && !remaining.isZero()) {
                    jwtBlacklistService.blacklist(claims.tokenId(), remaining);
                }
            } catch (InvalidTokenException e) {
                // Already expired or invalid: nothing to block.
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {

        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private AuthResult issueTokens(User user, RefreshTokenService.IssuedRefreshToken refreshToken) {

        JwtService.IssuedAccessToken accessToken = jwtService.issue(user);

        AuthResponse response = new AuthResponse(
                accessToken.value(),
                "Bearer",
                jwtService.getAccessTokenTtl().toSeconds(),
                UserResponse.from(user)
        );

        return new AuthResult(response, refreshToken);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
