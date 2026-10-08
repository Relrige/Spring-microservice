package ua.edu.ukma.springers.voltstore.authservice.integration;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ua.edu.ukma.springers.voltstore.authservice.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.authservice.config.JwtProperties;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;
import ua.edu.ukma.springers.voltstore.authservice.repositories.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserLoginIntegrationTest {
    private static final String LOGIN_URL = "/auth/login";
    private static final String REGISTER_URL = "/user/register";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanUsersTable() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void login_registeredCustomer_returnsVerifiableTokenWithClaims() throws Exception {
        String userId = register("alice@example.com", "secret1");

        String token = login("alice@example.com", "secret1");

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new MACVerifier(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.getStringClaim("role")).isEqualTo("CUSTOMER");
        assertThat(claims.getExpirationTime()).isAfter(new Date());
        assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())
                .isEqualTo(jwtProperties.expiration().toMillis());
    }

    @Test
    void login_staffUser_tokenCarriesTheirRole() throws Exception {
        userRepository.save(UserEntity.builder().email("manager@example.com")
                .passwordHash(passwordEncoder.encode("secret1")).role(UserRole.CATALOG_MANAGER).build());

        String token = login("manager@example.com", "secret1");

        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("role")).isEqualTo("CATALOG_MANAGER");
    }

    @Test
    void login_emailIsCaseInsensitive() throws Exception {
        register("alice@example.com", "secret1");

        assertThat(login("  ALICE@Example.com ", "secret1")).isNotBlank();
    }

    @Test
    void login_unknownEmailAndWrongPassword_areIndistinguishable() throws Exception {
        register("alice@example.com", "secret1");

        MvcResult wrongPassword = mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(json("alice@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownEmail = mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(json("nobody@example.com", "secret1")))
                .andExpect(status().isUnauthorized()).andReturn();

        JsonNode wrongPasswordBody = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode unknownEmailBody = objectMapper.readTree(unknownEmail.getResponse().getContentAsString());
        // The timestamp legitimately differs between two calls; everything else must match exactly
        ((tools.jackson.databind.node.ObjectNode) wrongPasswordBody).remove("timestamp");
        ((tools.jackson.databind.node.ObjectNode) unknownEmailBody).remove("timestamp");
        assertThat(wrongPasswordBody).isEqualTo(unknownEmailBody);
        assertThat(wrongPasswordBody.get("detail").asString()).isEqualTo("Invalid credentials");
    }

    @Test
    void login_missingOrEmptyFields_returns400() throws Exception {
        mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(json("", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void configuredExpirationIsPositive() {
        assertThat(jwtProperties.expiration()).isGreaterThan(Duration.ZERO);
    }

    private String register(String email, String password) throws Exception {
        String body = mockMvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content(json(email, password)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("userId").asString();
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content(json(email, password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asString();
    }

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
