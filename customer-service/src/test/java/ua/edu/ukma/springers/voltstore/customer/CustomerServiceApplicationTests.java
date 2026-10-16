package ua.edu.ukma.springers.voltstore.customer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
@SpringBootTest
class CustomerServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
