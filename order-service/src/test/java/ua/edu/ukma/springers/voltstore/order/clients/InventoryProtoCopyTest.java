package ua.edu.ukma.springers.voltstore.order.clients;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order compiles its gRPC client from a copy of Inventory's contract (each service builds alone). The copy must be
 * the same file, or Order would call methods or send fields Inventory does not know.
 */
class InventoryProtoCopyTest {
    private static final String PROTO = "src/main/protobuf/inventory_stock.proto";

    @Test
    void orderUsesTheSameContractAsInventory() throws IOException {
        Path owner = Path.of("..", "inventory-service").resolve(PROTO);
        Path copy = Path.of(PROTO);

        assertThat(normalized(copy))
                .as("%s must be an exact copy of %s; copy it again after changing the contract", copy, owner)
                .isEqualTo(normalized(owner));
    }

    // Git may check the files out with different line endings
    private static String normalized(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }
}
