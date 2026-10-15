package ua.edu.ukma.springers.voltstore.payment.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Stands in for the charge and refund endpoints, which are written in tasks 06 and 07, so that the security rules
 * can be tested now. {@code @TestComponent} keeps it out of component scanning: it exists only where a test imports
 * it. Delete it once the real controller exists, and test the security against that controller instead.
 */
@TestComponent
@RestController
public class PaymentRoutesProbeController {

    public record ProbeBody(@NotNull UUID orderId) {
    }

    @PostMapping("/payments/charge")
    public ResponseEntity<Void> charge(@Valid @RequestBody ProbeBody body) {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/payments/refund")
    public ResponseEntity<Void> refund(@Valid @RequestBody ProbeBody body) {
        return ResponseEntity.ok().build();
    }
}
