package ua.edu.ukma.springers.voltstore.order.support;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.order.services.OrderAccess;

import java.util.Map;
import java.util.UUID;

/**
 * Stands in for the Order endpoints not written yet (tasks 09–14), so that the security rules and the ownership check
 * can be tested now; {@code POST /orders/validate} and {@code POST /orders} are served by the real
 * {@code OrderController}. {@code @TestComponent} keeps it out of component scanning: it exists only where a test
 * imports it. Remove each route as its real controller is written.
 */
@TestComponent
@RestController
@RequiredArgsConstructor
public class OrderRoutesProbeController {
    private final OrderAccess orderAccess;

    @PostMapping("/orders/{orderId}/pay")
    public void pay(@PathVariable UUID orderId) {
    }

    @GetMapping("/orders")
    public void history() {
    }

    @GetMapping("/orders/{orderId}")
    public Map<String, Object> details(@PathVariable UUID orderId, @AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("orderId", orderAccess.loadOwned(orderId, user).getId());
    }

    @DeleteMapping("/orders/{orderId}")
    public void cancel(@PathVariable UUID orderId) {
    }

    @GetMapping("/orders/fulfillment-queue")
    public void queue() {
    }

    @PostMapping("/orders/{orderId}/ship")
    public void ship(@PathVariable UUID orderId) {
    }
}
