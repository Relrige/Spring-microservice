package ua.edu.ukma.springers.voltstore.notification.jobs;

/**
 * The result of one sending attempt. {@code recipient} is the address used, possibly just resolved from the read
 * model (then it is stored with the result); null if none was known.
 */
sealed interface SendOutcome {

    String recipient();

    record Sent(String recipient) implements SendOutcome {
    }

    /**
     * @param reason    stored as {@code failure_reason}; never contains the address
     * @param retryable whether a later attempt may succeed
     */
    record Failure(String recipient, String reason, boolean retryable) implements SendOutcome {
    }
}
