package ua.edu.ukma.springers.voltstore.customer.support;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Calls the cart endpoints as one customer, with the headers the API Gateway would set. */
public record CartApi(MockMvc mockMvc, UUID customerId) {

    public ResultActions get() throws Exception {
        // Fully qualified: this method's name hides a static import of MockMvcRequestBuilders.get
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/customers/cart").headers(TrustedHeaders.asCustomer(customerId)));
    }

    public ResultActions add(UUID productId, int quantity) throws Exception {
        return addRaw("{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}");
    }

    public ResultActions addRaw(String body) throws Exception {
        return mockMvc.perform(post("/customers/cart/items").headers(TrustedHeaders.asCustomer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public ResultActions setQuantity(Object productId, String body) throws Exception {
        return mockMvc.perform(patch("/customers/cart/items/" + productId)
                .headers(TrustedHeaders.asCustomer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public ResultActions setQuantity(UUID productId, int quantity) throws Exception {
        return setQuantity(productId, "{\"quantity\":" + quantity + "}");
    }

    public ResultActions remove(UUID productId) throws Exception {
        return mockMvc.perform(delete("/customers/cart/items/" + productId)
                .headers(TrustedHeaders.asCustomer(customerId)));
    }

    public ResultActions replace(String body) throws Exception {
        return mockMvc.perform(put("/customers/cart").headers(TrustedHeaders.asCustomer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public ResultActions merge(String body) throws Exception {
        return mockMvc.perform(post("/customers/cart/merge").headers(TrustedHeaders.asCustomer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public ResultActions clear() throws Exception {
        return mockMvc.perform(delete("/customers/cart").headers(TrustedHeaders.asCustomer(customerId)));
    }

    /** Builds {@code {"items":[...]}} from alternating product IDs and quantities. */
    public static String items(Object... productIdsAndQuantities) {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < productIdsAndQuantities.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"productId\":\"").append(productIdsAndQuantities[i])
                    .append("\",\"quantity\":").append(productIdsAndQuantities[i + 1]).append('}');
        }
        return json.append("]}").toString();
    }

    public static void cleanTables(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM cart_items");
        jdbc.update("DELETE FROM carts");
    }

    public static int cartRows(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class);
    }
}
