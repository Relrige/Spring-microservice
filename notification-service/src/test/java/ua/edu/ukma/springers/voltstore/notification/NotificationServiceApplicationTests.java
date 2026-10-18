package ua.edu.ukma.springers.voltstore.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
@SpringBootTest
class NotificationServiceApplicationTests {

    @Autowired
    private Flyway flyway;

    @Test
    void contextLoads_withEveryMigrationApplied() {
        assertThat(flyway.info().pending()).isEmpty();
    }

}
