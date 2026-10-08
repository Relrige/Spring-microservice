package ua.edu.ukma.springers.voltstore.authservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.EmailNotUniqueException;
import ua.edu.ukma.springers.voltstore.authservice.services.UserService;

/**
 * Creates the initial administrator on startup so that the admin-only endpoint can be used at all.
 * Idempotent: if the account already exists, nothing happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AdminProperties.class)
public class AdminSeeder implements ApplicationRunner {
    private final AdminProperties adminProperties;
    private final UserService userService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            userService.createUser(adminProperties.email(), adminProperties.password(), UserRole.ADMIN);
            log.info("Seeded initial admin user");
        } catch (EmailNotUniqueException e) {
            log.info("Initial admin user already exists, skipping seeding");
        }
    }
}
