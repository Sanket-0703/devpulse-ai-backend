package com.devpulse.backend.controller;

import com.devpulse.backend.dto.LoginRequest;
import com.devpulse.backend.dto.LoginResponse;
import com.devpulse.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.devpulse.backend.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
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
}