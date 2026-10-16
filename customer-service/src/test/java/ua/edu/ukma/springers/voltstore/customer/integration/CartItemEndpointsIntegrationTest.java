package ua.edu.ukma.springers.voltstore.customer.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.customer.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.support.CartApi;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Task 06 (EP-CUST-03 to EP-CUST-06)
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
class CartItemEndpointsIntegrationTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private CartApi cart;

    @BeforeEach
    void setUp() {
        CartApi.cleanTables(jdbc);
        cart = new CartApi(mockMvc, UUID.randomUUID());
    }

    @Test
    void getWithoutCart_returnsEmptyArray_andCreatesNoRow() throws Exception {
        cart.get().andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        assertThat(CartApi.cartRows(jdbc)).isZero();
    }

    @Test
    void add_newThenExisting_returnsTheWholeCartOrderedByProduct() throws Exception {
        cart.add(B, 1).andExpect(status().isOk());
        cart.add(A, 2).andExpect(status().isOk());

        cart.add(A, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(A.toString()))
                .andExpect(jsonPath("$[0].quantity").value(5))
                .andExpect(jsonPath("$[1].productId").value(B.toString()))
                .andExpect(jsonPath("$[1].quantity").value(1));

        cart.get().andExpect(jsonPath("$[0].quantity").value(5));
    }

    @Test
    void add_incrementPastTen_returns422() throws Exception {
        cart.add(A, 6).andExpect(status().isOk());

        cart.add(A, 5)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("QUANTITY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.productIds[0]").value(A.toString()))
                .andExpect(jsonPath("$.maxQuantity").value(10));

        cart.get().andExpect(jsonPath("$[0].quantity").value(6));
    }

    @Test
    void add_newProductWithQuantityEleven_returns422() throws Exception {
        cart.add(A, 11).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("QUANTITY_LIMIT_EXCEEDED"));
    }

    @Test
    void add_eleventhProduct_returns422() throws Exception {
        for (int i = 0; i < 10; i++) {
            cart.add(UUID.randomUUID(), 1).andExpect(status().isOk());
        }

        cart.add(UUID.randomUUID(), 1)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("TOO_MANY_PRODUCTS"))
                .andExpect(jsonPath("$.resultingProductCount").value(11))
                .andExpect(jsonPath("$.maxProducts").value(10));

        cart.get().andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    void add_malformedRequests_return400() throws Exception {
        cart.addRaw("{\"quantity\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.productId").isNotEmpty());
        cart.addRaw("{\"productId\":\"not-a-uuid\",\"quantity\":1}").andExpect(status().isBadRequest());
        cart.addRaw("{\"productId\":\"" + A + "\",\"quantity\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").isNotEmpty());
        cart.addRaw("{\"productId\":\"" + A + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").isNotEmpty());
        cart.addRaw("{\"productId\":\"" + A + "\",\"quantity\":1.5}").andExpect(status().isBadRequest());

        assertThat(CartApi.cartRows(jdbc)).isZero();
    }

    @Test
    void setQuantity_setsTheExactValue() throws Exception {
        cart.add(A, 7).andExpect(status().isOk());

        cart.setQuantity(A, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(2));
    }

    @Test
    void setQuantity_onMissingLineOrMissingCart_returns404_andCreatesNoRow() throws Exception {
        cart.setQuantity(A, 1).andExpect(status().isNotFound());
        assertThat(CartApi.cartRows(jdbc)).isZero();

        cart.add(B, 1).andExpect(status().isOk());
        cart.setQuantity(A, 1).andExpect(status().isNotFound());
    }

    @Test
    void setQuantity_eleven_returns422_zero_returns400() throws Exception {
        cart.add(A, 1).andExpect(status().isOk());

        cart.setQuantity(A, 11).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("QUANTITY_LIMIT_EXCEEDED"));
        cart.setQuantity(A, 0).andExpect(status().isBadRequest());
        cart.setQuantity("not-a-uuid", "{\"quantity\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.productId").isNotEmpty());

        cart.get().andExpect(jsonPath("$[0].quantity").value(1));
    }

    @Test
    void remove_isIdempotent_andWorksWithoutCart() throws Exception {
        cart.remove(A).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(CartApi.cartRows(jdbc)).isZero();

        cart.add(A, 1).andExpect(status().isOk());
        cart.add(B, 1).andExpect(status().isOk());

        cart.remove(A).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        cart.remove(A).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(B.toString()));
    }

    @Test
    void cartsOfDifferentCustomers_areIsolated() throws Exception {
        cart.add(A, 3).andExpect(status().isOk());
        CartApi other = new CartApi(mockMvc, UUID.randomUUID());

        other.get().andExpect(jsonPath("$.length()").value(0));
        other.add(A, 1).andExpect(jsonPath("$[0].quantity").value(1));
        other.remove(A).andExpect(status().isOk());

        cart.get().andExpect(jsonPath("$[0].quantity").value(3));
    }
}
