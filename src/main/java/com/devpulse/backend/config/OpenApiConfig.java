package com.devpulse.backend.config;

import com.devpulse.backend.dto.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API docs at /swagger-ui.html (browse and try endpoints) and
 * /v3/api-docs (JSON the frontend generates its TypeScript types from).
 * The "Authorize" button in Swagger UI takes a JWT from /api/auth/login.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "DevPulse AI API",
                version = "v1",
                description = "AI code review, vulnerability detection and semantic code search"
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    // ErrorResponse is only returned from GlobalExceptionHandler, which the
    // docs don't scan, so register its schema so the frontend gets the type.
    @Bean
    public OpenApiCustomizer errorResponseSchema() {
        return openApi -> ModelConverters.getInstance()
                .readAll(ErrorResponse.class)
                .forEach(openApi.getComponents()::addSchemas);
    }
}
