package com.devpulse.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class BackgroundConfig {

    /**
     * Runs background work such as repository syncs. Java 21 virtual
     * threads: cheap enough to start one per task, and they don't tie up an
     * OS thread while waiting on GitHub's network responses.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService backgroundExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
