package com.devpulse.backend.security;

import com.devpulse.backend.service.JwtBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    
    private final JwtService jwtService;
    private final CustomUserDetailsService customUserDetailsService;
    private final JwtBlacklistService jwtBlacklistService;


    public JwtAuthenticationFilter(
            JwtService jwtService,
            CustomUserDetailsService customUserDetailsService,
            JwtBlacklistService jwtBlacklistService) {

        this.jwtService = jwtService;
        this.customUserDetailsService =
                customUserDetailsService;
        this.jwtBlacklistService =
                jwtBlacklistService;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        String tokenId =
                jwtService.extractTokenId(token);

        if (jwtBlacklistService.isBlacklisted(tokenId)) {
            filterChain.doFilter(request, response);
            return;
        }

        String username =
                jwtService.extractUsername(token);

        if (username != null && jwtService.isTokenValid(token)) {

            UserDetails userDetails =
                    customUserDetailsService
                            .loadUserByUsername(username);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );

            SecurityContextHolder.getContext()
                    .setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}