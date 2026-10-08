package ua.edu.ukma.springers.voltstore.authservice.integration;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import ua.edu.ukma.springers.voltstore.authservice.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.authservice.support.TrustedHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NonCustomerUserIntegrationTest {
    private static final String URL = "/user/non-customer";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanUsersTable() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void adminCreatesStaffUser_whoCanThenLoginWithTheirRole() throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(json("worker@example.com", "secret1", "INVENTORY_WORKER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject("SELECT user_role FROM users", String.class)).isEqualTo("INVENTORY_WORKER");
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users", String.class)).isNotEqualTo("secret1");

        String body = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"worker@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asString();
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("role")).isEqualTo("INVENTORY_WORKER");
    }

    @Test
    void adminCanCreateAnotherAdmin() throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(json("admin2@example.com", "secret1", "ADMIN")))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT user_role FROM users", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void customerRoleCannotBeCreated_andNothingIsPersisted() throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(json("c@example.com", "secret1", "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void unauthorizedCallers_areRejected_andNothingIsPersisted() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(json("a@example.com", "secret1", "CATALOG_MANAGER")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("CATALOG_MANAGER")).contentType(MediaType.APPLICATION_JSON)
                        .content(json("b@example.com", "secret1", "CATALOG_MANAGER")))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void duplicateEmail_returns409() throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(json("dup@example.com", "secret1", "CATALOG_MANAGER")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(json("DUP@example.com", "secret1", "INVENTORY_WORKER")))
                .andExpect(status().isConflict());
    }

    private static String json(String email, String password, String role) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}";
    }
}
