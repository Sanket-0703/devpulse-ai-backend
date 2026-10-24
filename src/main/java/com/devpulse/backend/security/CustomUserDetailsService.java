package com.devpulse.backend.security;

import com.devpulse.backend.entity.User;
import com.devpulse.backend.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public CustomUserPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {

        return userRepository.findByEmailIgnoreCase(email)
                .map(CustomUserDetailsService::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /** Access tokens carry the user id, so the filter looks users up by id. */
    public CustomUserPrincipal loadUserById(Long id) throws UsernameNotFoundException {

        return userRepository.findById(id)
                .map(CustomUserDetailsService::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private static CustomUserPrincipal toPrincipal(User user) {
        return new CustomUserPrincipal(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPassword(),
                user.getRole(),
                user.getTokensValidAfter()
        );
    }
}
