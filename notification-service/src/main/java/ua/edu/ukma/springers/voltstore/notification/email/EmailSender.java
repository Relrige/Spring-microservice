package ua.edu.ukma.springers.voltstore.notification.email;

/**
 * Outbound port for sending email (spec D4), like Delivery's {@code CarrierGateway}: the sender job knows only this
 * interface, the adapter knows the protocol. The only adapter is {@link SmtpEmailSender}.
 */
public interface EmailSender {

    /**
     * Returns once the mail server accepted the message.
     *
     * @throws EmailSendException if it did not; {@link EmailSendException#isRetryable()} says whether a later
     *                            attempt may succeed
     */
    void send(EmailMessage message);
}
