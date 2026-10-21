package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.*;
import com.devpulse.backend.exception.InvalidCredentialsException;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.UserService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.EmailAlreadyExistsException;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserServiceImpl(
            UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }


    @Override
    public ProfileResponse updateProfile(
            Long userId,
            ProfileUpdateRequest request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (userRepository.existsByEmailAndIdNot(
                request.getEmail(),
                userId)) {

            throw new EmailAlreadyExistsException(
                    "Email already exists"
            );
        }

        user.setName(request.getName());
        user.setEmail(request.getEmail());

        userRepository.save(user);

        return new ProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole()
        );
    }

    @Override
    public void changePassword(
            Long userId,
            ChangePasswordRequest request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new RuntimeException("User not found"));

        if (!passwordEncoder.matches(
                request.getCurrentPassword(),
                user.getPassword())) {

            throw new InvalidCredentialsException(
                    "Current password is incorrect");
        }

        user.setPassword(
                passwordEncoder.encode(
                        request.getNewPassword()
                )
        );

        userRepository.save(user);
    }
}