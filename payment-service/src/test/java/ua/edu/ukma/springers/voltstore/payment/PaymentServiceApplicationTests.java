package ua.edu.ukma.springers.voltstore.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ua.edu.ukma.springers.voltstore.payment.gateway.MockPaymentGateway;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentGateway;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentServiceApplicationTests {

	@Autowired
	private PaymentGateway paymentGateway;

	@Test
	void contextLoads_withTheMockGatewaySelectedByConfiguration() {
		// Also proves Flyway's schema passes Hibernate's validate
		assertThat(paymentGateway).isInstanceOf(MockPaymentGateway.class);
	}

}
