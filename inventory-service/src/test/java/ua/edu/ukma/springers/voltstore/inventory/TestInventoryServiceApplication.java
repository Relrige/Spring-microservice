package ua.edu.ukma.springers.voltstore.inventory;

import org.springframework.boot.SpringApplication;

public class TestInventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(InventoryServiceApplication::main).with(PostgresTestContainerConfig.class, KafkaTestContainerConfig.class).run(args);
	}

}
