package ua.edu.ukma.springers.voltstore.authservice.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.authservice.TestcontainersConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserRegistrationIntegrationTest {
    private static final String URL = "/auth/register";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUsersTable() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void register_persistsCustomerWithHashedPassword() throws Exception {
        String body = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("alice@example.com", "secret1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users");
        assertThat(body).contains(row.get("id").toString());
        assertThat(row.get("email")).isEqualTo("alice@example.com");
        assertThat(row.get("user_role")).isEqualTo("CUSTOMER");
        assertThat(row.get("created_at")).isNotNull();
        String hash = (String) row.get("password_hash");
        assertThat(hash).isNotEqualTo("secret1");
        assertThat(passwordEncoder.matches("secret1", hash)).isTrue();
    }

    @Test
    void register_ignoresRoleSentByClient() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"mallory@example.com\",\"password\":\"secret1\",\"role\":\"INVENTORY_WORKER\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT user_role FROM users", String.class)).isEqualTo("CUSTOMER");
    }

    @Test
    void register_duplicateEmail_returns409AndKeepsSingleRow() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("alice@example.com", "secret1")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("alice@example.com", "other-pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Email already in use"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void register_emailDiffersOnlyByCase_returns409() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("alice@example.com", "secret1")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Alice@Example.COM", "secret1")))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void register_invalidInput_returns400AndPersistsNothing() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("bad", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").isNotEmpty())
                .andExpect(jsonPath("$.errors.password").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void register_concurrentRequestsWithSameEmail_exactlyOneCreatedRestConflict() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    startTogether.await();
                    return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                                    .content(json("race@example.com", "secret1")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
