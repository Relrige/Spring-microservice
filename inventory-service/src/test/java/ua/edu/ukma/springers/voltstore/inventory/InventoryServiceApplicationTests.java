package ua.edu.ukma.springers.voltstore.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
@SpringBootTest
class InventoryServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
