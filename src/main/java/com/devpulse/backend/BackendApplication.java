package com.devpulse.backend;

import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.config.CorsProperties;
import com.devpulse.backend.config.DemoProperties;
import com.devpulse.backend.config.FrontendProperties;
import com.devpulse.backend.config.GitHubProperties;
import com.devpulse.backend.config.JwtProperties;
import com.devpulse.backend.config.RefreshCookieProperties;
import com.devpulse.backend.config.S3Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
		JwtProperties.class,
		GitHubProperties.class,
		FrontendProperties.class,
		S3Properties.class,
		AIProperties.class,
		CorsProperties.class,
		RefreshCookieProperties.class,
		DemoProperties.class
})
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}
}