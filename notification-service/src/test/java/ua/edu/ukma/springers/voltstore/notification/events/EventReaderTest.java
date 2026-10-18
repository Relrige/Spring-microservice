package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.notification.exceptions.PoisonMessageException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventReaderTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private final EventReader reader = new EventReader(JsonMapper.builder().build(), VALIDATION.getValidator());

    record Sample(@NotNull UUID eventId, @NotBlank @Email String customerEmail, @NotNull BigDecimal totalAmount) {
    }

    @AfterAll
    static void close() {
        VALIDATION.close();
    }

    @Test
    void validPayload_isRead_andUnknownPropertiesAreIgnored() {
        UUID eventId = UUID.randomUUID();

        Sample sample = reader.read("""
                {"eventId":"%s","customerEmail":"user@example.com","totalAmount":1500.00,"items":[{"x":1}]}
                """.formatted(eventId), Sample.class);

        assertThat(sample.eventId()).isEqualTo(eventId);
        assertThat(sample.totalAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void notJson_isPoison() {
        assertThatThrownBy(() -> reader.read("this is not JSON", Sample.class))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessage("Unreadable Sample: not valid JSON");
    }

    @Test
    void emptyOrNullPayload_isPoison() {
        assertThatThrownBy(() -> reader.read(null, Sample.class)).isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> reader.read("null", Sample.class)).isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void wrongValueType_namesThePropertyButNotTheValue() {
        assertThatThrownBy(() -> reader.read("""
                {"eventId":"user@example.com","customerEmail":"user@example.com","totalAmount":1}
                """, Sample.class))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessageContaining("eventId")
                .hasMessageNotContaining("user@example.com")
                .hasNoCause();
    }

    @Test
    void brokenConstraints_areListedWithoutValues() {
        assertThatThrownBy(() -> reader.read("""
                {"customerEmail":"not an address","totalAmount":1}
                """, Sample.class))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessage("Invalid Sample: customerEmail must be a well-formed email address, "
                        + "eventId must not be null");
    }
}
