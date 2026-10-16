package ua.edu.ukma.springers.voltstore.customer.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.customer.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.support.Concurrently;
import ua.edu.ukma.springers.voltstore.customer.support.TrustedHeaders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Task 04 (EP-CUST-01, EP-CUST-02)
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class CustomerProfileIntegrationTest {
    private static final String VALID = """
            {"firstName":"Ivan","lastName":"Petrenko","phone":"+380501234567","defaultAddress":"Kyiv, branch 12"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM customer_profiles");
    }

    @Test
    void getWithoutProfile_returnsAllFieldsNull_andCreatesNoRow() throws Exception {
        mockMvc.perform(get("/customers/me/profile").headers(TrustedHeaders.asCustomer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").isEmpty())
                .andExpect(jsonPath("$.lastName").isEmpty())
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.defaultAddress").isEmpty());

        assertThat(rows()).isZero();
    }

    @Test
    void put_insertsThenUpdates_andGetReturnsTheSavedProfile() throws Exception {
        putProfile(VALID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ivan"))
                .andExpect(jsonPath("$.defaultAddress").value("Kyiv, branch 12"));

        putProfile("""
                {"firstName":"Olena","lastName":"Koval","phone":"+380671112233"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Olena"))
                .andExpect(jsonPath("$.defaultAddress").isEmpty());

        mockMvc.perform(get("/customers/me/profile").headers(TrustedHeaders.asCustomer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Koval"))
                .andExpect(jsonPath("$.phone").value("+380671112233"));
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void profilesOfDifferentCustomers_areIsolated() throws Exception {
        putProfile(VALID).andExpect(status().isOk());

        mockMvc.perform(get("/customers/me/profile").headers(TrustedHeaders.asCustomer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").isEmpty());
    }

    @Test
    void blankNames_return400WithFieldErrors() throws Exception {
        putProfile("""
                {"firstName":" ","lastName":"","phone":"+380501234567"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").isNotEmpty())
                .andExpect(jsonPath("$.errors.lastName").isNotEmpty());
        assertThat(rows()).isZero();
    }

    @Test
    void invalidOrMissingPhone_returns400() throws Exception {
        for (String phone : new String[]{"\"0501234567\"", "\"+38 050 123 45 67\"", "\"+0501234567\"", "null"}) {
            putProfile("{\"firstName\":\"Ivan\",\"lastName\":\"Petrenko\",\"phone\":" + phone + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.phone").isNotEmpty());
        }
    }

    @Test
    void tooLongFields_return400() throws Exception {
        String longName = "a".repeat(101);
        putProfile("{\"firstName\":\"" + longName + "\",\"lastName\":\"Petrenko\",\"phone\":\"+380501234567\","
                + "\"defaultAddress\":\"" + "b".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").isNotEmpty())
                .andExpect(jsonPath("$.errors.defaultAddress").isNotEmpty());
    }

    @Test
    void parallelFirstSaves_endAsOneRowWithoutErrors() throws Exception {
        List<Object> statuses = Concurrently.run(8, () -> putProfile(VALID).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(rows()).isEqualTo(1);
    }

    private ResultActions putProfile(String body) throws Exception {
        return mockMvc.perform(put("/customers/me/profile").headers(TrustedHeaders.asCustomer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM customer_profiles", Integer.class);
    }
}
