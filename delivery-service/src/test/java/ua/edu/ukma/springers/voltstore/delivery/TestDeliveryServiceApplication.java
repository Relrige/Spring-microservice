package ua.edu.ukma.springers.voltstore.delivery;

import org.springframework.boot.SpringApplication;

public class TestDeliveryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(DeliveryServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
