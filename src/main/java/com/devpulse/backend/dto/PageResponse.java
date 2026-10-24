package com.devpulse.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One page of a list. Our own shape instead of serializing Spring's Page
 * directly, whose JSON layout is internal and has changed between versions.
 *
 * @param page zero-based page number
 */
public record PageResponse<T>(
        @Schema(requiredMode = REQUIRED) List<T> content,
        @Schema(requiredMode = REQUIRED) int page,
        @Schema(requiredMode = REQUIRED) int size,
        @Schema(requiredMode = REQUIRED) long totalElements,
        @Schema(requiredMode = REQUIRED) int totalPages
) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.map(mapper).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
