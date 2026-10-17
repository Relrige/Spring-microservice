package ua.edu.ukma.springers.voltstore.order.support;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Stands in for the Order endpoints not written yet (task 14), so that the security rules can be tested now;
 * the other routes are served by the real {@code OrderController}. {@code @TestComponent} keeps it out of component
 * scanning: it exists only where a test imports it. Remove each route as its real controller is written.
 */
@TestComponent
@RestController
public class OrderRoutesProbeController {

    @GetMapping("/orders/fulfillment-queue")
    public void queue() {
    }

    @PostMapping("/orders/{orderId}/ship")
    public void ship(@PathVariable UUID orderId) {
    }
}
