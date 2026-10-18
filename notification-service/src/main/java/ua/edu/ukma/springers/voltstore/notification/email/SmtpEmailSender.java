package ua.edu.ukma.springers.voltstore.notification.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SMTP adapter of {@link EmailSender} on Spring's {@link JavaMailSender} ({@code spring.mail.*}, timeouts in
 * application.yml). In Compose and Kubernetes it talks to Mailpit; a real provider needs only other settings.
 * <p>
 * <b>Failure classification</b> (spec, <i>Sending email</i>):
 * <ul>
 *   <li>a {@code 5xx} reply to the recipient ({@code RCPT TO}) → not retryable, {@code recipient_rejected: <code>};
 *   an address that cannot even be parsed → not retryable, {@code recipient_invalid};</li>
 *   <li>any other SMTP reply → retryable, {@code smtp_temporary: <code>} (a {@code 4xx}) or
 *   {@code smtp_rejected: <code>} (another {@code 5xx}, e.g. for the sender: a configuration problem that may be
 *   fixed);</li>
 *   <li>authentication failure → retryable, {@code smtp_auth_failed}, logged at {@code ERROR} as a configuration
 *   problem;</li>
 *   <li>everything else (connection refused, timeout, unknown host) → retryable,
 *   {@code smtp_unavailable: <exception>: <message>}.</li>
 * </ul>
 * Server replies are reduced to their code: their text often repeats the address. Neither the address nor the body
 * is ever logged.
 */
@Slf4j
@Component
public class SmtpEmailSender implements EmailSender {
    private final JavaMailSender mailSender;
    private final InternetAddress from;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${notification.mail.from}") String from)
            throws AddressException {
        this.mailSender = mailSender;
        // Parsed once, so a broken NOTIFICATION_MAIL_FROM fails at startup rather than on every email
        this.from = new InternetAddress(from, true);
    }

    @Override
    public void send(EmailMessage message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(new InternetAddress(message.to(), true));
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);
        } catch (MessagingException e) {
            throw new EmailSendException("recipient_invalid", false, null);
        }
        try {
            mailSender.send(mime);
        } catch (MailException e) {
            throw classify(e);
        }
    }

    static EmailSendException classify(MailException e) {
        if (e instanceof MailAuthenticationException) {
            log.error("SMTP authentication failed: check SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD; "
                    + "notifications are retried");
            return new EmailSendException("smtp_auth_failed", true, e);
        }
        List<Throwable> causes = causesOf(e);
        Optional<SMTPAddressFailedException> recipient = find(causes, SMTPAddressFailedException.class);
        if (recipient.isPresent()) {
            int code = recipient.get().getReturnCode();
            return is5xx(code)
                    ? new EmailSendException("recipient_rejected: " + code, false, null)
                    : new EmailSendException("smtp_temporary: " + code, true, null);
        }
        Optional<Integer> replyCode = find(causes, SMTPSendFailedException.class)
                .map(SMTPSendFailedException::getReturnCode)
                .or(() -> find(causes, SMTPSenderFailedException.class).map(SMTPSenderFailedException::getReturnCode));
        if (replyCode.isPresent()) {
            int code = replyCode.get();
            return new EmailSendException((is5xx(code) ? "smtp_rejected: " : "smtp_temporary: ") + code, true, null);
        }
        Throwable root = causes.getLast();
        return new EmailSendException("smtp_unavailable: " + root.getClass().getSimpleName() + ": " + root.getMessage(),
                true, root);
    }

    private static boolean is5xx(int code) {
        return code >= 500 && code < 600;
    }

    private static <T> Optional<T> find(List<Throwable> causes, Class<T> type) {
        return causes.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    // The exception, its causes, the per-message failures of a MailSendException and the chain of nested
    // MessagingExceptions (JavaMail reports per-recipient failures through getNextException)
    private static List<Throwable> causesOf(MailException e) {
        List<Throwable> causes = new ArrayList<>();
        collect(e, causes);
        if (e instanceof MailSendException send) {
            send.getFailedMessages().values().forEach(failure -> collect(failure, causes));
        }
        return causes;
    }

    private static void collect(Throwable throwable, List<Throwable> causes) {
        for (Throwable t = throwable; t != null && !causes.contains(t); ) {
            causes.add(t);
            Throwable next = t instanceof MessagingException messaging ? messaging.getNextException() : null;
            if (next != null && next != t.getCause()) {
                collect(next, causes);
            }
            t = t.getCause();
        }
    }
}
