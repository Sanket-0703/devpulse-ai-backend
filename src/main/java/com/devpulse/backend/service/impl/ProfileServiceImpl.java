package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.EmailAddresses;
import com.devpulse.backend.dto.ProfileUpdateRequest;
import com.devpulse.backend.dto.UserResponse;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.EmailAlreadyExistsException;
import com.devpulse.backend.exception.FieldValidationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.ProfileService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileServiceImpl implements ProfileService {

    private static final String EMAIL_TAKEN = "An account with this email already exists";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ProfileServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return UserResponse.from(findUser(userId));
    }

    @Override
    @Transactional
    public UserResponse updateProfile(Long userId, ProfileUpdateRequest request) {

        User user = findUser(userId);
        String newEmail = EmailAddresses.normalize(request.getEmail());

        if (!newEmail.equals(user.getEmail())) {
            requireCurrentPassword(user, request.getCurrentPassword());

            if (userRepository.existsByEmailIgnoreCaseAndIdNot(newEmail, userId)) {
                throw new EmailAlreadyExistsException(EMAIL_TAKEN);
            }
            user.setEmail(newEmail);
        }

        user.setName(request.getName().trim());

        try {
            // Flush now so a unique-constraint race surfaces here (as 409),
            // not later when the transaction commits.
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyExistsException(EMAIL_TAKEN);
        }
    }

    private void requireCurrentPassword(User user, String currentPassword) {

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new FieldValidationException("currentPassword",
                    "Enter your current password to change your email");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new FieldValidationException("currentPassword", "Current password is incorrect");
        }
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
