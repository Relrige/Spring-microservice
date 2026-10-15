package ua.edu.ukma.springers.voltstore.payment.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.payment.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;
import ua.edu.ukma.springers.voltstore.payment.entities.PaymentTransactionEntity;
import ua.edu.ukma.springers.voltstore.payment.entities.TransactionStatus;
import ua.edu.ukma.springers.voltstore.payment.entities.TransactionType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionType.CHARGE;
import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionType.REFUND;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TestcontainersConfiguration.class)
class PaymentTransactionRepositoryTest {
    private static final BigDecimal AMOUNT = new BigDecimal("1499.90");

    @Autowired
    private PaymentTransactionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM payment_transactions");
    }

    @Test
    void successfulCharge_isStoredAndReadBack() {
        UUID orderId = UUID.randomUUID();

        PaymentTransactionEntity saved = repository.saveAndFlush(success(CHARGE, orderId, "charge:" + orderId + ":1"));

        PaymentTransactionEntity found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getType()).isEqualTo(CHARGE);
        assertThat(found.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(found.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(found.getCurrency()).isEqualTo("UAH");
        assertThat(found.getGatewayTransactionId()).isEqualTo("gw-" + orderId);
        assertThat(found.getFailureReason()).isNull();
        assertThat(found.getIdempotencyKey()).isEqualTo("charge:" + orderId + ":1");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void enums_areStoredAsStrings() {
        PaymentTransactionEntity saved = repository.saveAndFlush(
                failure(CHARGE, UUID.randomUUID(), FailureReason.GATEWAY_TIMEOUT, "k"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT type, status, failure_reason FROM payment_transactions WHERE id = ?", saved.getId());
        assertThat(row).containsEntry("type", "CHARGE").containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "GATEWAY_TIMEOUT");
    }

    @Test
    void secondSuccessfulCharge_ofAnOrder_violatesTheUniqueIndex() {
        UUID orderId = UUID.randomUUID();
        repository.saveAndFlush(success(CHARGE, orderId, "k1"));

        assertThatThrownBy(() -> repository.saveAndFlush(success(CHARGE, orderId, "k2")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_payment_transactions_successful_charge");
    }

    @Test
    void secondSuccessfulRefund_ofAnOrder_violatesTheUniqueIndex() {
        UUID orderId = UUID.randomUUID();
        repository.saveAndFlush(success(CHARGE, orderId, "c"));
        repository.saveAndFlush(success(REFUND, orderId, "r"));

        assertThatThrownBy(() -> repository.saveAndFlush(success(REFUND, orderId, "r")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_payment_transactions_successful_refund");
    }

    @Test
    void failedCharges_mayRepeatForAnOrder_andBeFollowedByASuccess() {
        UUID orderId = UUID.randomUUID();
        repository.saveAndFlush(failure(CHARGE, orderId, FailureReason.INSUFFICIENT_FUNDS, "k1"));
        repository.saveAndFlush(failure(CHARGE, orderId, FailureReason.CARD_DECLINED, "k2"));
        repository.saveAndFlush(success(CHARGE, orderId, "k3"));

        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void nonPositiveAmount_violatesTheCheck() {
        PaymentTransactionEntity zero = PaymentTransactionEntity.failed(CHARGE, UUID.randomUUID(), BigDecimal.ZERO,
                "UAH", FailureReason.CARD_DECLINED, "k");

        assertThatThrownBy(() -> repository.saveAndFlush(zero)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void currencyThatIsNotAnUpperCaseCode_violatesTheCheck() {
        PaymentTransactionEntity lowerCase = PaymentTransactionEntity.failed(CHARGE, UUID.randomUUID(), AMOUNT,
                "uah", FailureReason.CARD_DECLINED, "k");

        assertThatThrownBy(() -> repository.saveAndFlush(lowerCase))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void successWithoutGatewayId_isRejectedByTheDatabaseToo() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO payment_transactions "
                + "(id, order_id, type, status, amount, currency, idempotency_key) "
                + "VALUES (?, ?, 'CHARGE', 'SUCCESS', 10, 'UAH', 'k')", id, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_payment_transactions_success_has_gateway_id");
    }

    @Test
    void existsAndFind_matchOnlyTheGivenTypeAndStatus() {
        UUID orderId = UUID.randomUUID();
        repository.saveAndFlush(failure(CHARGE, orderId, FailureReason.INSUFFICIENT_FUNDS, "k1"));
        PaymentTransactionEntity charge = repository.saveAndFlush(success(CHARGE, orderId, "k2"));

        assertThat(repository.existsByOrderIdAndTypeAndStatus(orderId, CHARGE, TransactionStatus.SUCCESS)).isTrue();
        assertThat(repository.existsByOrderIdAndTypeAndStatus(orderId, REFUND, TransactionStatus.SUCCESS)).isFalse();
        assertThat(repository.findByOrderIdAndTypeAndStatus(orderId, CHARGE, TransactionStatus.SUCCESS))
                .get().extracting(PaymentTransactionEntity::getId).isEqualTo(charge.getId());
    }

    @Test
    void countAttemptsEndedBy_countsDistinctKeysOfThisOrderWithTheGivenReasons() {
        UUID orderId = UUID.randomUUID();
        // Two rows of one attempt (concurrent requests sharing a key) count once
        repository.saveAndFlush(failure(CHARGE, orderId, FailureReason.INSUFFICIENT_FUNDS, "charge:1"));
        repository.saveAndFlush(failure(CHARGE, orderId, FailureReason.INSUFFICIENT_FUNDS, "charge:1"));
        repository.saveAndFlush(failure(CHARGE, orderId, FailureReason.CARD_DECLINED, "charge:2"));
        // An uncertain outcome does not end the attempt
        repository.saveAndFlush(failure(CHARGE, orderId, FailureReason.GATEWAY_TIMEOUT, "charge:3"));
        // Other orders and other types do not count
        repository.saveAndFlush(failure(CHARGE, UUID.randomUUID(), FailureReason.CARD_DECLINED, "other"));
        repository.saveAndFlush(failure(REFUND, orderId, FailureReason.REFUND_REJECTED, "refund"));

        assertThat(repository.countAttemptsEndedBy(orderId, CHARGE, FailureReason.definitiveReasons())).isEqualTo(2);
    }

    private static PaymentTransactionEntity success(TransactionType type, UUID orderId, String key) {
        return PaymentTransactionEntity.succeeded(type, orderId, AMOUNT, "UAH", "gw-" + orderId, key);
    }

    private static PaymentTransactionEntity failure(TransactionType type, UUID orderId, FailureReason reason,
                                                    String key) {
        return PaymentTransactionEntity.failed(type, orderId, AMOUNT, "UAH", reason, key);
    }
}
