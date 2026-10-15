package ua.edu.ukma.springers.voltstore.delivery.services;

import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;

/** The outcome of one TTN registration attempt (see {@link TtnRegistrationService}). */
public sealed interface TtnAttemptResult {

    record Success(String ttn, Parcel parcel) implements TtnAttemptResult {
    }

    /**
     * @param reason    the stable failure reason shown in the fulfillment queue
     * @param retryable whether a later attempt may succeed without anyone fixing data
     */
    record Failure(String reason, boolean retryable) implements TtnAttemptResult {
    }
}
