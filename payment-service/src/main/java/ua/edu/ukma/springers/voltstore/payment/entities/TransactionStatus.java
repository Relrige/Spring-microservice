package ua.edu.ukma.springers.voltstore.payment.entities;

/**
 * Outcome of one gateway request. There is no pending state: a row is written once the gateway has answered
 * (or failed to answer), and never changed afterwards.
 */
public enum TransactionStatus {
    SUCCESS,
    FAILED
}
