package com.devpulse.backend;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.config.CorsProperties;
import com.devpulse.backend.config.GitHubOAuthProperties;
import com.devpulse.backend.config.JwtProperties;
import com.devpulse.backend.config.RefreshCookieProperties;
import com.devpulse.backend.config.S3Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
		JwtProperties.class,
		GitHubOAuthProperties.class,
		S3Properties.class,
		AIProperties.class,
		CorsProperties.class,
		RefreshCookieProperties.class
})
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}
}