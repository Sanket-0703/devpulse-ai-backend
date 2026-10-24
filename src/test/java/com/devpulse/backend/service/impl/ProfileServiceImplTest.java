package com.devpulse.backend.service.impl;

import com.devpulse.backend.dto.ProfileUpdateRequest;
import com.devpulse.backend.dto.UserResponse;
import com.devpulse.backend.entity.Role;
import com.devpulse.backend.entity.User;
import com.devpulse.backend.exception.EmailAlreadyExistsException;
import com.devpulse.backend.exception.FieldValidationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private ProfileServiceImpl profileService;
    private User user;

    @BeforeEach
    void setUp() {
        profileService = new ProfileServiceImpl(userRepository, passwordEncoder);

        user = new User();
        user.setId(1L);
        user.setName("Ada");
        user.setEmail("ada@example.com");
        user.setPassword("bcrypt-hash");
        user.setRole(Role.ROLE_USER);
    }

    @Test
    void changingOnlyTheNameNeedsNoPassword() {

        givenUserExists();
        givenSaveReturnsUser();

        UserResponse response = profileService.updateProfile(1L, request("  Ada Lovelace ", "ada@example.com", null));

        assertThat(response.name()).isEqualTo("Ada Lovelace");
        assertThat(response.email()).isEqualTo("ada@example.com");
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void sameEmailInDifferentCaseIsNotAChange() {

        givenUserExists();
        givenSaveReturnsUser();

        profileService.updateProfile(1L, request("Ada", " ADA@Example.com ", null));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(userRepository, never()).existsByEmailIgnoreCaseAndIdNot(anyString(), any());
    }

    @Test
    void changingTheEmailRequiresTheCurrentPassword() {

        givenUserExists();

        assertThatThrownBy(() -> profileService.updateProfile(1L, request("Ada", "new@example.com", " ")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("currentPassword");
                    assertThat(e.getMessage()).isEqualTo("Enter your current password to change your email");
                });

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void changingTheEmailWithAWrongPasswordFails() {

        givenUserExists();
        when(passwordEncoder.matches("wrong", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> profileService.updateProfile(1L, request("Ada", "new@example.com", "wrong")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Current password is incorrect"));
    }

    @Test
    void changingTheEmailToOneThatIsTakenFails() {

        givenUserExists();
        when(passwordEncoder.matches("right", "bcrypt-hash")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("taken@example.com", 1L)).thenReturn(true);

        assertThatThrownBy(() -> profileService.updateProfile(1L, request("Ada", "Taken@Example.com", "right")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void changingTheEmailWithTheRightPasswordNormalizesIt() {

        givenUserExists();
        givenSaveReturnsUser();
        when(passwordEncoder.matches("right", "bcrypt-hash")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("new@example.com", 1L)).thenReturn(false);

        UserResponse response = profileService.updateProfile(1L, request("Ada", " New@Example.com ", "right"));

        assertThat(response.email()).isEqualTo("new@example.com");
    }

    @Test
    void aUniqueConstraintRaceIsReportedAsAConflict() {

        givenUserExists();
        when(passwordEncoder.matches("right", "bcrypt-hash")).thenReturn(true);
        when(userRepository.existsByEmailIgnoreCaseAndIdNot(anyString(), any())).thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> profileService.updateProfile(1L, request("Ada", "new@example.com", "right")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void unknownUserIs404() {

        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.getProfile(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private void givenUserExists() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    }

    private void givenSaveReturnsUser() {
        when(userRepository.saveAndFlush(user)).thenReturn(user);
    }

    private static ProfileUpdateRequest request(String name, String email, String currentPassword) {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setName(name);
        request.setEmail(email);
        request.setCurrentPassword(currentPassword);
        return request;
    }
}
