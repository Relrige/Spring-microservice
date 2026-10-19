package ua.edu.ukma.springers.voltstore.notification.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.notification.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;
import ua.edu.ukma.springers.voltstore.notification.jobs.NotificationSenderJob;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;
import ua.edu.ukma.springers.voltstore.notification.support.MailpitContainer;
import ua.edu.ukma.springers.voltstore.notification.support.MutableClock;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 11: the sender job's counter and the pending gauge, as Prometheus scrapes them (without credentials).
 * {@code @AutoConfigureMetrics}: Spring Boot tests switch metrics export off unless asked.
 */
@SpringBootTest(properties = "notification.sender.interval=1h")
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, MailpitContainer.Config.class,
        MutableClock.Config.class})
class NotificationMetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationSenderJob job;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void sentAndFailedAttempts_andThePendingBacklog_areScraped() throws Exception {
        insert("buyer@example.com", NotificationType.ORDER_PLACED, 0);
        // Mailpit accepts every address, so a permanent failure is provoked with one that cannot be parsed
        insert("not an address", NotificationType.ORDER_SHIPPED, 0);
        // Not due yet: stays PENDING
        insert("later@example.com", NotificationType.PAYMENT_RECEIVED, 60);

        assertThat(job.runOnce()).isEqualTo(2);

        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(scrape)
                .containsPattern("voltstore_notifications_total\\{application=\"notification-service\","
                        + "outcome=\"sent\",type=\"ORDER_PLACED\"} 1\\.0")
                .containsPattern("voltstore_notifications_total\\{application=\"notification-service\","
                        + "outcome=\"failed\",type=\"ORDER_SHIPPED\"} 1\\.0")
                .containsPattern("voltstore_notifications_pending\\{application=\"notification-service\"} 1\\.0")
                // Bounded labels only
                .doesNotContain("example.com", "recipient_invalid");
    }

    private void insert(String recipient, NotificationType type, long dueInSeconds) {
        UUID orderId = UUID.randomUUID();
        notifications.insertIfAbsent(NotificationEntity.pending(UUID.randomUUID(), orderId, type, recipient,
                "VoltStore — Order #" + orderId, "Hello", clock.instant().plusSeconds(dueInSeconds)));
    }
}
