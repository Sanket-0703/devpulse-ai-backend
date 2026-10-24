package com.devpulse.backend.security;

import com.devpulse.backend.service.JwtBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Authenticates requests that carry "Authorization: Bearer <access token>".
 *
 * It never rejects a request itself: a missing or bad token just leaves the
 * request anonymous. Protected endpoints then answer 401 through
 * {@link JsonSecurityErrorHandler}, using the reason stored in
 * {@link #AUTH_ERROR_ATTRIBUTE}; public endpoints still work.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Why authentication failed, shown in the 401 message. */
    public static final String AUTH_ERROR_ATTRIBUTE = "devpulse.authError";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService customUserDetailsService;
    private final JwtBlacklistService jwtBlacklistService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            CustomUserDetailsService customUserDetailsService,
            JwtBlacklistService jwtBlacklistService) {

        this.jwtService = jwtService;
        this.customUserDetailsService = customUserDetailsService;
        this.jwtBlacklistService = jwtBlacklistService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(request, header.substring(BEARER_PREFIX.length()).trim());
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {

        try {
            JwtService.AccessTokenClaims claims = jwtService.parse(token);

            if (jwtBlacklistService.isBlacklisted(claims.tokenId())) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Session has ended, please sign in again");
                return;
            }

            CustomUserPrincipal principal = customUserDetailsService.loadUserById(claims.userId());

            // Issued before a password change or "sign out everywhere".
            Instant cutoff = principal.getTokensValidAfter();
            if (cutoff != null && claims.issuedAt().isBefore(cutoff)) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Session has ended, please sign in again");
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (InvalidTokenException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getMessage());

        } catch (UsernameNotFoundException e) {
            // Valid token for an account that has since been deleted.
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Account no longer exists");
        }
    }
}
