package ua.edu.ukma.springers.voltstore.apigetaway;

import org.springframework.boot.SpringApplication;

public class TestApiGetawayApplication {

    public static void main(String[] args) {
        SpringApplication.from(ApiGetawayApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
