package com.devpulse.backend.controller;

import com.devpulse.backend.dto.LoginRequest;
import com.devpulse.backend.dto.LoginResponse;
import com.devpulse.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.devpulse.backend.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.devpulse.backend.service.JwtBlacklistService;
import com.devpulse.backend.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.Date;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtBlacklistService jwtBlacklistService;
    private final JwtService jwtService;

    public AuthController(
            UserService userService,
            JwtBlacklistService jwtBlacklistService,
            JwtService jwtService) {

        this.userService = userService;
        this.jwtBlacklistService = jwtBlacklistService;
        this.jwtService = jwtService;
    }
    @PostMapping("/register")
    public String register(@Valid @RequestBody RegisterRequest request) {

        userService.register(request);

        return "User Registered Successfully";
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request) {

        return userService.login(request);
    }

    @RestController
    @RequestMapping("/api/test")
    public class TestController {

        @GetMapping
        public String test(Authentication authentication) {
            return authentication.getName();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request) {

        String authHeader =
                request.getHeader("Authorization");

        if (authHeader == null
                || !authHeader.startsWith("Bearer ")) {

            return ResponseEntity.noContent().build();
        }

        String token =
                authHeader.substring(7);

        String tokenId =
                jwtService.extractTokenId(token);

        Date expiration =
                jwtService.extractExpiration(token);

        long remainingMillis =
                expiration.getTime()
                        - System.currentTimeMillis();

        if (remainingMillis > 0) {

            jwtBlacklistService.blacklist(
                    tokenId,
                    Duration.ofMillis(
                            remainingMillis
                    )
            );
        }

        return ResponseEntity.noContent().build();
    }
}