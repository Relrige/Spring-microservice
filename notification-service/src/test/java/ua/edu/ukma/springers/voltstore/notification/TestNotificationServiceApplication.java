package ua.edu.ukma.springers.voltstore.notification;

import org.springframework.boot.SpringApplication;

public class TestNotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(NotificationServiceApplication::main)
                .with(PostgresTestContainerConfig.class, KafkaTestContainerConfig.class)
                .run(args);
    }

}
