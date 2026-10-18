package ua.edu.ukma.springers.voltstore.notification.email;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The mapping of JavaMail failures, as JavaMailSenderImpl reports them, to retryable and permanent reasons
class SmtpEmailSenderClassificationTest {
    private static final String ADDRESS = "user@example.com";

    @Test
    void recipientRejectedWith5xx_isPermanent_andDoesNotRepeatTheAddress() throws Exception {
        EmailSendException e = SmtpEmailSender.classify(failed(new SendFailedException("Invalid Addresses",
                new SMTPAddressFailedException(new InternetAddress(ADDRESS), "RCPT TO:<" + ADDRESS + ">", 550,
                        "550 5.1.1 <" + ADDRESS + ">: Recipient address rejected"))));

        assertThat(e.isRetryable()).isFalse();
        assertThat(e.getReason()).isEqualTo("recipient_rejected: 550");
        assertThat(e).hasNoCause();
    }

    @Test
    void recipientDeferredWith4xx_isRetryable() throws Exception {
        EmailSendException e = SmtpEmailSender.classify(failed(new SendFailedException("Invalid Addresses",
                new SMTPAddressFailedException(new InternetAddress(ADDRESS), "RCPT TO", 451, "451 try later"))));

        assertThat(e.isRetryable()).isTrue();
        assertThat(e.getReason()).isEqualTo("smtp_temporary: 451");
    }

    @Test
    void otherSmtpReplies_areRetryable() throws Exception {
        EmailSendException temporary = SmtpEmailSender.classify(failed(
                new SMTPSendFailedException("DATA", 421, "421 service not available", null, null, null, null)));
        EmailSendException rejected = SmtpEmailSender.classify(failed(
                new SMTPSendFailedException("DATA", 554, "554 message rejected", null, null, null, null)));

        assertThat(temporary.isRetryable()).isTrue();
        assertThat(temporary.getReason()).isEqualTo("smtp_temporary: 421");
        assertThat(rejected.isRetryable()).isTrue();
        assertThat(rejected.getReason()).isEqualTo("smtp_rejected: 554");
    }

    @Test
    void connectionRefused_isRetryable() {
        MailConnectException refused = new MailConnectException(
                new SocketConnectException("Using socket factory", new ConnectException("Connection refused"),
                        "mailpit", 1025, 5000));

        EmailSendException e = SmtpEmailSender.classify(failed(refused));

        assertThat(e.isRetryable()).isTrue();
        assertThat(e.getReason()).isEqualTo("smtp_unavailable: ConnectException: Connection refused");
    }

    @Test
    void timeout_isRetryable() {
        EmailSendException e = SmtpEmailSender.classify(
                failed(new MessagingException("Exception reading response", new SocketTimeoutException("Read timed out"))));

        assertThat(e.isRetryable()).isTrue();
        assertThat(e.getReason()).isEqualTo("smtp_unavailable: SocketTimeoutException: Read timed out");
    }

    @Test
    void authenticationFailure_isRetryable() {
        EmailSendException e = SmtpEmailSender.classify(new MailAuthenticationException("535 authentication failed"));

        assertThat(e.isRetryable()).isTrue();
        assertThat(e.getReason()).isEqualTo("smtp_auth_failed");
    }

    // JavaMailSenderImpl reports a failed message as a MailSendException keyed by the message
    private static MailSendException failed(Exception cause) {
        return new MailSendException(Map.of(new MimeMessage((jakarta.mail.Session) null), cause));
    }
}
