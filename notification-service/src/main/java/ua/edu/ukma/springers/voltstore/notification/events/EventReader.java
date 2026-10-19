package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.notification.exceptions.PoisonMessageException;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads a record's JSON text into the handler's own record type and validates it (tolerant reader: unknown
 * properties are ignored). Anything that cannot become a valid event is a {@link PoisonMessageException}. The
 * messages name only the type and the broken properties, never values: the payloads contain email addresses.
 */
@Component
@RequiredArgsConstructor
public class EventReader {
    private final JsonMapper jsonMapper;
    private final Validator validator;

    public <T> T read(String payload, Class<T> type) {
        if (payload == null) {
            throw new PoisonMessageException("Empty " + type.getSimpleName(), null);
        }
        T event;
        try {
            event = jsonMapper.readerFor(type)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(payload);
        } catch (DatabindException e) {
            // Jackson's message quotes the offending value, so only the property path is kept (and no cause, which
            // the error handler would log with that message); the record itself is in the dead-letter topic
            throw new PoisonMessageException("Unreadable " + type.getSimpleName() + ": invalid value at "
                    + e.getPathReference(), null);
        } catch (JacksonException e) {
            throw new PoisonMessageException("Unreadable " + type.getSimpleName() + ": not valid JSON", null);
        }
        if (event == null) {
            throw new PoisonMessageException("Empty " + type.getSimpleName(), null);
        }
        Set<ConstraintViolation<T>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new PoisonMessageException("Invalid " + type.getSimpleName() + ": " + details, null);
        }
        return event;
    }
}
