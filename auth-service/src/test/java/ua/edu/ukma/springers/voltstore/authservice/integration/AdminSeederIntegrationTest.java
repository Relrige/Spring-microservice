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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import ua.edu.ukma.springers.voltstore.authservice.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.authservice.config.AdminProperties;
import ua.edu.ukma.springers.voltstore.authservice.config.AdminSeeder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminSeederIntegrationTest {

    @Autowired
    private AdminSeeder adminSeeder;

    @Autowired
    private AdminProperties adminProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanUsersTable() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void seeder_createsAdminWithHashedPassword() {
        adminSeeder.run(null);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users");
        assertThat(row.get("email")).isEqualTo(adminProperties.email());
        assertThat(row.get("user_role")).isEqualTo("ADMIN");
        String hash = (String) row.get("password_hash");
        assertThat(hash).isNotEqualTo(adminProperties.password());
        assertThat(passwordEncoder.matches(adminProperties.password(), hash)).isTrue();
    }

    @Test
    void seeder_isIdempotent() {
        adminSeeder.run(null);
        adminSeeder.run(null);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void seededAdmin_canLoginAndTokenCarriesAdminRole() throws Exception {
        adminSeeder.run(null);

        String body = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + adminProperties.email() + "\",\"password\":\""
                                + adminProperties.password() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(body).get("token").asString();
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("role")).isEqualTo("ADMIN");
    }
}
