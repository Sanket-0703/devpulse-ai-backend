package com.devpulse.backend.repository;

import com.devpulse.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);
    User findByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

}