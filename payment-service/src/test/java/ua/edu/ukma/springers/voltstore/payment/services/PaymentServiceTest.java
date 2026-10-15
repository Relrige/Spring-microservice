package ua.edu.ukma.springers.voltstore.payment.services;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;
import ua.edu.ukma.springers.voltstore.payment.entities.PaymentTransactionEntity;
import ua.edu.ukma.springers.voltstore.payment.entities.TransactionStatus;
import ua.edu.ukma.springers.voltstore.payment.exceptions.NoSuccessfulChargeException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyChargedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyRefundedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayUnavailableException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundAmountMismatchException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundRejectedException;
import ua.edu.ukma.springers.voltstore.payment.gateway.ChargeCommand;
import ua.edu.ukma.springers.voltstore.payment.gateway.DeclineReason;
import ua.edu.ukma.springers.voltstore.payment.gateway.GatewayResult;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentCard;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentGateway;
import ua.edu.ukma.springers.voltstore.payment.gateway.RefundCommand;
import ua.edu.ukma.springers.voltstore.payment.repositories.PaymentTransactionRepository;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionStatus.SUCCESS;
import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionType.CHARGE;
import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionType.REFUND;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SAVED_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BigDecimal AMOUNT = new BigDecimal("1499.90");
    private static final PaymentCard CARD = new PaymentCard("4242424242424242", 12, 2035, "987");

    @Mock
    private PaymentTransactionRepository repository;

    @Mock
    private PaymentGateway gateway;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(repository, gateway);
        // Saving returns the entity with an ID, as the database would
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentTransactionEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", SAVED_ID);
            return entity;
        });
    }

    @Nested
    class Charge {

        @Test
        void alreadyCharged_is409_andTheGatewayIsNotCalled() {
            when(repository.existsByOrderIdAndTypeAndStatus(ORDER_ID, CHARGE, SUCCESS)).thenReturn(true);

            assertThatThrownBy(() -> charge()).isInstanceOf(OrderAlreadyChargedException.class);
            verifyNoInteractions(gateway);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        void approved_recordsTheSuccessWithKeyAndGatewayId() {
            when(gateway.charge(any())).thenReturn(new GatewayResult.Approved("gw-1"));

            assertThat(charge()).isEqualTo(SAVED_ID);

            PaymentTransactionEntity saved = savedEntity();
            assertThat(saved.getType()).isEqualTo(CHARGE);
            assertThat(saved.getStatus()).isEqualTo(SUCCESS);
            assertThat(saved.getGatewayTransactionId()).isEqualTo("gw-1");
            assertThat(saved.getIdempotencyKey()).isEqualTo("charge:" + ORDER_ID + ":1");
            assertThat(sentCharge().card()).isSameAs(CARD);
        }

        @Test
        void key_countsOnlyAttemptsEndedByADefinitiveDecline() {
            when(repository.countAttemptsEndedBy(ORDER_ID, CHARGE, FailureReason.definitiveReasons())).thenReturn(2L);
            when(gateway.charge(any())).thenReturn(new GatewayResult.Approved("gw-1"));

            charge();

            assertThat(sentCharge().idempotencyKey()).isEqualTo("charge:" + ORDER_ID + ":3");
        }

        @Test
        void declined_recordsTheReason_andIs402() {
            when(gateway.charge(any())).thenReturn(new GatewayResult.Declined(DeclineReason.INSUFFICIENT_FUNDS));

            assertThatThrownBy(() -> charge()).isInstanceOfSatisfying(PaymentDeclinedException.class,
                    e -> assertThat(e.getReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS));
            assertFailureSaved(FailureReason.INSUFFICIENT_FUNDS, "charge:" + ORDER_ID + ":1");
        }

        @Test
        void timeout_isRecordedAsUncertain_andPassedOn() {
            when(gateway.charge(any())).thenThrow(new PaymentGatewayTimeoutException("no answer"));

            assertThatThrownBy(() -> charge()).isInstanceOf(PaymentGatewayTimeoutException.class);
            assertFailureSaved(FailureReason.GATEWAY_TIMEOUT, "charge:" + ORDER_ID + ":1");
        }

        @Test
        void unavailable_isRecordedAsUncertain_andPassedOn() {
            when(gateway.charge(any()))
                    .thenThrow(new PaymentGatewayUnavailableException("refused", new ConnectException()));

            assertThatThrownBy(() -> charge()).isInstanceOf(PaymentGatewayUnavailableException.class);
            assertFailureSaved(FailureReason.GATEWAY_UNAVAILABLE, "charge:" + ORDER_ID + ":1");
        }

        @Test
        void keyMismatch_is409_andNothingIsRecorded() {
            when(gateway.charge(any())).thenReturn(new GatewayResult.IdempotencyKeyMismatch());

            assertThatThrownBy(() -> charge()).isInstanceOf(PaymentAttemptInProgressException.class);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        void losingAConcurrentRace_onTheSuccessIndex_is409() {
            when(gateway.charge(any())).thenReturn(new GatewayResult.Approved("gw-1"));
            doThrow(violationOf(PaymentService.SUCCESSFUL_CHARGE_INDEX)).when(repository).saveAndFlush(any());

            assertThatThrownBy(() -> charge()).isInstanceOf(OrderAlreadyChargedException.class);
        }

        @Test
        void anyOtherConstraintViolation_isNotHidden() {
            when(gateway.charge(any())).thenReturn(new GatewayResult.Approved("gw-1"));
            doThrow(violationOf("chk_payment_transactions_currency")).when(repository).saveAndFlush(any());

            assertThatThrownBy(() -> charge()).isInstanceOf(DataIntegrityViolationException.class);
        }

        private UUID charge() {
            return service.charge(ORDER_ID, AMOUNT, "UAH", CARD);
        }

        private ChargeCommand sentCharge() {
            ArgumentCaptor<ChargeCommand> captor = ArgumentCaptor.forClass(ChargeCommand.class);
            verify(gateway).charge(captor.capture());
            return captor.getValue();
        }

        private void assertFailureSaved(FailureReason reason, String key) {
            PaymentTransactionEntity saved = savedEntity();
            assertThat(saved.getType()).isEqualTo(CHARGE);
            assertThat(saved.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(saved.getFailureReason()).isEqualTo(reason);
            assertThat(saved.getIdempotencyKey()).isEqualTo(key);
        }
    }

    @Nested
    class Refund {
        private final PaymentTransactionEntity charge =
                PaymentTransactionEntity.succeeded(CHARGE, ORDER_ID, AMOUNT, "UAH", "gw-charge", "charge:1");

        @Test
        void noSuccessfulCharge_is422_andTheGatewayIsNotCalled() {
            when(repository.findByOrderIdAndTypeAndStatus(ORDER_ID, CHARGE, SUCCESS)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refund(AMOUNT)).isInstanceOf(NoSuccessfulChargeException.class);
            verifyNoInteractions(gateway);
        }

        @Test
        void alreadyRefunded_is409_andTheGatewayIsNotCalled() {
            givenCharge();
            when(repository.existsByOrderIdAndTypeAndStatus(ORDER_ID, REFUND, SUCCESS)).thenReturn(true);

            assertThatThrownBy(() -> refund(AMOUNT)).isInstanceOf(OrderAlreadyRefundedException.class);
            verifyNoInteractions(gateway);
        }

        @Test
        void partialAmount_is422() {
            givenCharge();

            assertThatThrownBy(() -> refund(new BigDecimal("1000.00")))
                    .isInstanceOf(RefundAmountMismatchException.class);
            verifyNoInteractions(gateway);
        }

        @Test
        void otherCurrency_is422() {
            givenCharge();

            assertThatThrownBy(() -> service.refund(ORDER_ID, AMOUNT, "EUR"))
                    .isInstanceOf(RefundAmountMismatchException.class);
        }

        @Test
        void approved_refundsTheChargeByItsGatewayId_withOneKeyPerOrder() {
            givenCharge();
            when(gateway.refund(any())).thenReturn(new GatewayResult.Approved("gw-refund"));

            // 1499.9 equals 1499.90
            assertThat(refund(new BigDecimal("1499.9"))).isEqualTo(SAVED_ID);

            verify(gateway).refund(eq(new RefundCommand("refund:" + ORDER_ID, "gw-charge", new BigDecimal("1499.9"),
                    "UAH")));
            PaymentTransactionEntity saved = savedEntity();
            assertThat(saved.getType()).isEqualTo(REFUND);
            assertThat(saved.getStatus()).isEqualTo(SUCCESS);
            assertThat(saved.getGatewayTransactionId()).isEqualTo("gw-refund");
        }

        @Test
        void rejected_isRecorded_andIs502() {
            givenCharge();
            when(gateway.refund(any())).thenReturn(new GatewayResult.Declined(DeclineReason.REFUND_REJECTED));

            assertThatThrownBy(() -> refund(AMOUNT)).isInstanceOf(RefundRejectedException.class);
            assertThat(savedEntity().getFailureReason()).isEqualTo(FailureReason.REFUND_REJECTED);
        }

        @Test
        void timeout_isRecordedAsUncertain_andPassedOn() {
            givenCharge();
            when(gateway.refund(any())).thenThrow(new PaymentGatewayTimeoutException("no answer"));

            assertThatThrownBy(() -> refund(AMOUNT)).isInstanceOf(PaymentGatewayTimeoutException.class);
            assertThat(savedEntity().getFailureReason()).isEqualTo(FailureReason.GATEWAY_TIMEOUT);
        }

        @Test
        void losingAConcurrentRace_onTheSuccessIndex_is409() {
            givenCharge();
            when(gateway.refund(any())).thenReturn(new GatewayResult.Approved("gw-refund"));
            doThrow(violationOf(PaymentService.SUCCESSFUL_REFUND_INDEX)).when(repository).saveAndFlush(any());

            assertThatThrownBy(() -> refund(AMOUNT)).isInstanceOf(OrderAlreadyRefundedException.class);
        }

        private void givenCharge() {
            when(repository.findByOrderIdAndTypeAndStatus(ORDER_ID, CHARGE, SUCCESS)).thenReturn(Optional.of(charge));
        }

        private UUID refund(BigDecimal amount) {
            return service.refund(ORDER_ID, amount, "UAH");
        }
    }

    private PaymentTransactionEntity savedEntity() {
        ArgumentCaptor<PaymentTransactionEntity> captor = ArgumentCaptor.forClass(PaymentTransactionEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key", new SQLException("duplicate key"), constraint));
    }
}
