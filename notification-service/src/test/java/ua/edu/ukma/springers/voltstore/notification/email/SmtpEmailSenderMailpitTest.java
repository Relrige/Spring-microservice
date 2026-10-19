package ua.edu.ukma.springers.voltstore.notification.email;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ua.edu.ukma.springers.voltstore.notification.support.MailpitContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter against a real Mailpit (the same server as in Compose), without Spring: the JavaMailSender is
 * configured like {@code spring.mail.*} in application.yml, with shorter timeouts.
 */
class SmtpEmailSenderMailpitTest {
    private static final String FROM = "VoltStore <no-reply@voltstore.com>";
    private static final MailpitContainer MAILPIT = new MailpitContainer();

    @BeforeAll
    static void startMailpit() {
        MAILPIT.start();
    }

    @AfterAll
    static void stopMailpit() {
        MAILPIT.stop();
    }

    @BeforeEach
    void clearMailbox() {
        MAILPIT.clear();
    }

    @Test
    void sentMessage_arrivesWithSenderSubjectAndUtf8Body() throws Exception {
        SmtpEmailSender sender = new SmtpEmailSender(mailSender(MAILPIT.getHost(), MAILPIT.smtpPort(), 5000), FROM);

        sender.send(new EmailMessage("buyer@example.com", "VoltStore — Order #1 placed, awaiting payment",
                "Hello,\n\nДякуємо за замовлення — 1500.00 UAH\n"));

        List<MailpitContainer.Message> messages = MAILPIT.messages();
        assertThat(messages).hasSize(1);
        MailpitContainer.Message message = messages.getFirst();
        assertThat(message.to()).containsExactly("buyer@example.com");
        assertThat(message.from()).isEqualTo("no-reply@voltstore.com");
        assertThat(MAILPIT.fromName(message.id())).isEqualTo("VoltStore");
        assertThat(message.subject()).isEqualTo("VoltStore — Order #1 placed, awaiting payment");
        assertThat(MAILPIT.text(message.id())).contains("Дякуємо за замовлення — 1500.00 UAH");
    }

    @Test
    void unusedPort_failsRetryablyAtOnce() throws Exception {
        SmtpEmailSender sender = new SmtpEmailSender(mailSender("localhost", unusedPort(), 2000), FROM);

        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOfSatisfying(EmailSendException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getReason()).startsWith("smtp_unavailable: ");
                });
    }

    @Test
    void serverThatNeverAnswers_failsRetryablyWithinTheTimeout() throws Exception {
        // Accepts the connection but never sends the SMTP greeting
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket ignored = silent.accept()) {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (IOException | InterruptedException ignored) {
                    // the test is over
                }
            });
            SmtpEmailSender sender = new SmtpEmailSender(mailSender("localhost", silent.getLocalPort(), 1000), FROM);

            long start = System.nanoTime();
            assertThatThrownBy(() -> sender.send(message()))
                    .isInstanceOfSatisfying(EmailSendException.class, e -> {
                        assertThat(e.isRetryable()).isTrue();
                        assertThat(e.getReason()).startsWith("smtp_unavailable: ");
                    });
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            acceptor.interrupt();
        }
    }

    @Test
    void unparsableAddress_failsPermanently() throws Exception {
        SmtpEmailSender sender = new SmtpEmailSender(mailSender(MAILPIT.getHost(), MAILPIT.smtpPort(), 5000), FROM);

        assertThatThrownBy(() -> sender.send(new EmailMessage("not an address", "s", "b")))
                .isInstanceOfSatisfying(EmailSendException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getReason()).isEqualTo("recipient_invalid");
                });
        assertThat(MAILPIT.messages()).isEmpty();
    }

    private static EmailMessage message() {
        return new EmailMessage("buyer@example.com", "subject", "body");
    }

    private static JavaMailSenderImpl mailSender(String host, int port, int timeoutMillis) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setDefaultEncoding("UTF-8");
        Properties properties = new Properties();
        properties.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMillis));
        properties.put("mail.smtp.timeout", String.valueOf(timeoutMillis));
        properties.put("mail.smtp.writetimeout", String.valueOf(timeoutMillis));
        mailSender.setJavaMailProperties(properties);
        return mailSender;
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
