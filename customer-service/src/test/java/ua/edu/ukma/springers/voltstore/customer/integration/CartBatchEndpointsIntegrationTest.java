package ua.edu.ukma.springers.voltstore.customer.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.customer.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.support.CartApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.customer.support.CartApi.items;

// Task 07 (EP-CUST-07 to EP-CUST-09)
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class CartBatchEndpointsIntegrationTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

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

    // --- PUT /customers/cart (replace) ---

    @Test
    void replace_onEmptyCart_createsTheCartWithTheItems() throws Exception {
        cart.replace(items(B, 2, A, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(A.toString()))
                .andExpect(jsonPath("$[1].quantity").value(2));

        assertThat(CartApi.cartRows(jdbc)).isEqualTo(1);
    }

    @Test
    void replace_onFullCart_discardsOldLines_andKeepsOverlappingOnes() throws Exception {
        cart.add(A, 1).andExpect(status().isOk());
        for (UUID id : products(9)) {
            cart.add(id, 1).andExpect(status().isOk());
        }

        cart.replace(items(A, 4, B, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productId").value(A.toString()))
                .andExpect(jsonPath("$[0].quantity").value(4))
                .andExpect(jsonPath("$[1].productId").value(B.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items", Integer.class)).isEqualTo(2);
    }

    @Test
    void replace_withTenItems_replacesAFullCart() throws Exception {
        for (UUID id : products(10)) {
            cart.add(id, 1).andExpect(status().isOk());
        }

        cart.replace(itemsOf(products(10), 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].quantity").value(3));
    }

    @Test
    void replace_sameRequestTwice_givesTheSameCart() throws Exception {
        String body = items(A, 2, B, 3);
        String first = cart.replace(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        cart.replace(body).andExpect(status().isOk()).andExpect(content().json(first));
    }

    @Test
    void replace_withEmptyList_emptiesTheCart() throws Exception {
        cart.add(A, 1).andExpect(status().isOk());

        cart.replace("{\"items\":[]}").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        assertThat(CartApi.cartRows(jdbc)).isEqualTo(1);
    }

    @Test
    void replace_ruleViolations_return422_andLeaveTheCartUnchanged() throws Exception {
        cart.add(A, 5).andExpect(status().isOk());

        cart.replace(itemsOf(products(11), 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("TOO_MANY_PRODUCTS"))
                .andExpect(jsonPath("$.resultingProductCount").value(11));
        cart.replace(items(B, 1, B, 2))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("DUPLICATE_PRODUCT"))
                .andExpect(jsonPath("$.productIds[0]").value(B.toString()));
        cart.replace(items(B, 11, C, 12))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("QUANTITY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.productIds.length()").value(2));

        cart.get().andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].quantity").value(5));
    }

    @Test
    void replace_malformedRequests_return400() throws Exception {
        cart.replace("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.items").isNotEmpty());
        cart.replace("{\"items\":[null]}").andExpect(status().isBadRequest());
        cart.replace("{\"items\":[{\"productId\":\"" + A + "\",\"quantity\":0}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[0].quantity']").isNotEmpty());
        cart.replace("{\"items\":[{\"quantity\":1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[0].productId']").isNotEmpty());

        assertThat(CartApi.cartRows(jdbc)).isZero();
    }

    // --- POST /customers/cart/merge ---

    @Test
    void merge_disjointCarts_addsTheNewLines() throws Exception {
        cart.add(A, 1).andExpect(status().isOk());

        cart.merge(items(B, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].quantity").value(2));
    }

    @Test
    void merge_overlappingProducts_areSummed_upToExactlyTen() throws Exception {
        cart.add(A, 4).andExpect(status().isOk());
        cart.add(B, 1).andExpect(status().isOk());

        cart.merge(items(A, 6, C, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(10))
                .andExpect(jsonPath("$[1].quantity").value(1))
                .andExpect(jsonPath("$[2].quantity").value(3));
    }

    @Test
    void merge_withoutCart_createsIt() throws Exception {
        cart.merge(items(A, 2)).andExpect(status().isOk()).andExpect(jsonPath("$[0].quantity").value(2));

        assertThat(CartApi.cartRows(jdbc)).isEqualTo(1);
    }

    @Test
    void merge_sumOverTen_returns422_listingEveryOffendingProduct() throws Exception {
        cart.add(A, 6).andExpect(status().isOk());
        cart.add(B, 9).andExpect(status().isOk());

        cart.merge(items(A, 5, B, 2, C, 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("QUANTITY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.productIds.length()").value(2))
                .andExpect(jsonPath("$.productIds[0]").value(A.toString()))
                .andExpect(jsonPath("$.productIds[1]").value(B.toString()));

        cart.get().andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].quantity").value(6));
    }

    @Test
    void merge_resultWithElevenProducts_returns422() throws Exception {
        for (UUID id : products(6)) {
            cart.add(id, 1).andExpect(status().isOk());
        }

        cart.merge(itemsOf(products(5), 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("TOO_MANY_PRODUCTS"))
                .andExpect(jsonPath("$.resultingProductCount").value(11));

        cart.get().andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    void merge_emptyList_withoutCart_returnsEmpty_andCreatesNoRow() throws Exception {
        cart.merge("{\"items\":[]}").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        assertThat(CartApi.cartRows(jdbc)).isZero();
    }

    @Test
    void merge_emptyList_returnsTheCurrentCart() throws Exception {
        cart.add(A, 3).andExpect(status().isOk());

        cart.merge("{\"items\":[]}").andExpect(status().isOk()).andExpect(jsonPath("$[0].quantity").value(3));
    }

    @Test
    void merge_duplicate_returns422() throws Exception {
        cart.merge(items(A, 1, A, 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("DUPLICATE_PRODUCT"));

        assertThat(CartApi.cartRows(jdbc)).isZero();
    }

    // --- DELETE /customers/cart (clear) ---

    @Test
    void clear_deletesItems_keepsTheCartRow_andIsIdempotent() throws Exception {
        cart.add(A, 1).andExpect(status().isOk());
        cart.add(B, 2).andExpect(status().isOk());

        cart.clear().andExpect(status().isOk()).andExpect(content().json("[]"));
        cart.clear().andExpect(status().isOk()).andExpect(content().json("[]"));

        assertThat(CartApi.cartRows(jdbc)).isEqualTo(1);
        cart.get().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void clear_withoutCart_createsNoRow() throws Exception {
        cart.clear().andExpect(status().isOk()).andExpect(content().json("[]"));

        assertThat(CartApi.cartRows(jdbc)).isZero();
    }

    private static List<UUID> products(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static String itemsOf(List<UUID> productIds, int quantity) {
        Object[] pairs = new Object[productIds.size() * 2];
        for (int i = 0; i < productIds.size(); i++) {
            pairs[2 * i] = productIds.get(i);
            pairs[2 * i + 1] = quantity;
        }
        return items(pairs);
    }
}
