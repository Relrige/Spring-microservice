package ua.edu.ukma.springers.voltstore.customer;

import org.springframework.boot.SpringApplication;

public class TestCustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(CustomerServiceApplication::main)
                .with(PostgresTestContainerConfig.class, KafkaTestContainerConfig.class)
                .run(args);
    }

}
