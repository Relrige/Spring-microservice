package ua.edu.ukma.springers.voltstore.inventory.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.UUID;

/** gRPC statuses of the StockService (inventory_stock.proto): bad input, a transient failure, or anything else. */
final class GrpcErrors {
    private GrpcErrors() {
    }

    static StatusRuntimeException invalid(String description) {
        return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
    }

    static UUID uuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be a UUID");
        }
    }

    /**
     * UNAVAILABLE only for failures worth retrying (the database briefly unreachable), so the client's retry policy
     * repeats exactly those; INTERNAL for the rest, which a repetition would not fix.
     */
    static Status toStatus(Throwable e) {
        if (e instanceof StatusRuntimeException status) {
            return status.getStatus();
        }
        if (e instanceof TransientDataAccessException || e instanceof DataAccessResourceFailureException
                || e instanceof CannotCreateTransactionException) {
            return Status.UNAVAILABLE.withDescription("Inventory's database is unavailable").withCause(e);
        }
        return Status.INTERNAL.withDescription("Inventory could not process the request").withCause(e);
    }
}
