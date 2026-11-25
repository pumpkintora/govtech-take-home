package com.chat.chat;

import com.chat.auth.AuthService;
import com.chat.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds three local demo accounts idempotently (`ON CONFLICT DO NOTHING`) so evaluators
 * can immediately test 1-to-1 messaging across two browser windows without manual setup.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AuthService authService;
    private final AppProperties properties;

    public DemoDataSeeder(AuthService authService, AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isSeedDemoUsers()) {
            return;
        }
        authService.seedUserIfMissing("alice", "Alice Tan", "password123");
        authService.seedUserIfMissing("bob", "Bob Lim", "password123");
        authService.seedUserIfMissing("charlie", "Charlie Ng", "password123");
        log.info("Demo users verified (alice, bob, charlie / password123)");
    }
}
