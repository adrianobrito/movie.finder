package com.moviefinder.config;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ApplicationLifecycleLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationLifecycleLogger.class);

    private final Environment environment;

    public ApplicationLifecycleLogger(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        LOGGER.info("Movie Finder is ready with active profiles {}", Arrays.toString(environment.getActiveProfiles()));
    }
}
