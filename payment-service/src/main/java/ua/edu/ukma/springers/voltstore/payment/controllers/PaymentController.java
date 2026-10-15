package ua.edu.ukma.springers.voltstore.payment.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.payment.dto.ChargeRequest;
import ua.edu.ukma.springers.voltstore.payment.dto.PaymentResponse;
import ua.edu.ukma.springers.voltstore.payment.dto.RefundRequest;
import ua.edu.ukma.springers.voltstore.payment.services.PaymentService;

/** Internal endpoints called by Order Service only (see SecurityConfig); never routed by the API Gateway. */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    // EP-PAY-01
    @PostMapping("/charge")
    public PaymentResponse charge(@Valid @RequestBody ChargeRequest request) {
        return PaymentResponse.success(paymentService.charge(request.orderId(), request.amount(), request.currency(),
                request.cardDetails().toPaymentCard()));
    }

    // EP-PAY-02
    @PostMapping("/refund")
    public PaymentResponse refund(@Valid @RequestBody RefundRequest request) {
        return PaymentResponse.success(paymentService.refund(request.orderId(), request.amount(), request.currency()));
    }
}
