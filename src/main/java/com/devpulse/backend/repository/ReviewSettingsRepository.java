package com.devpulse.backend.repository;

import com.devpulse.backend.entity.ReviewSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by user id. */
public interface ReviewSettingsRepository extends JpaRepository<ReviewSettings, Long> {
}
