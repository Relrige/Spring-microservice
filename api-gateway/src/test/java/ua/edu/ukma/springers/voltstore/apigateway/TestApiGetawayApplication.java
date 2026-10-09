package ua.edu.ukma.springers.voltstore.apigateway;

import org.springframework.boot.SpringApplication;

public class TestApiGetawayApplication {

    public static void main(String[] args) {
        SpringApplication.from(ApiGatewayApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
