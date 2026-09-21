package ua.edu.ukma.springers.voltstore.payment.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.payment.domain.entity.PaymentTransaction;
import ua.edu.ukma.springers.voltstore.payment.services.PaymentService;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ResponseEntity<PaymentTransaction> initiate(@RequestParam UUID orderId, @RequestParam BigDecimal amount) {
        return ResponseEntity.ok(paymentService.initiatePayment(orderId, amount));
    }

    @PostMapping("/callback")
    public ResponseEntity<PaymentTransaction> callback(@RequestParam UUID orderId,
                                                       @RequestParam String externalId,
                                                       @RequestParam boolean success) {
        return ResponseEntity.ok(paymentService.completePayment(orderId, externalId, success));
    }

    @PostMapping("/refund/{orderId}")
    public ResponseEntity<PaymentTransaction> refund(@PathVariable UUID orderId) {
        return ResponseEntity.ok(paymentService.refundPayment(orderId));
    }
}