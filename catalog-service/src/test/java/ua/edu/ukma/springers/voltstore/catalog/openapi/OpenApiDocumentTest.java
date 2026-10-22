package ua.edu.ukma.springers.voltstore.catalog.openapi;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Keeps the committed OpenAPI documents in step with the code (conventions D1): each group is built in-process and
 * compared, as a JSON tree (so line endings do not matter), with {@code openapi/<group>.json}. After a deliberate API
 * change, regenerate the files and commit them with the change:
 * <pre>mvnw.cmd test -Dtest=OpenApiDocumentTest -Dopenapi.update=true</pre>
 */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UPDATE_HINT =
            "Regenerate with: mvnw.cmd test -Dtest=OpenApiDocumentTest -Dopenapi.update=true";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"public", "internal"})
    void committedDocumentMatchesTheCode(String group) throws Exception {
        String generated = mockMvc.perform(get("/v3/api-docs/" + group))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Path file = Path.of("openapi", group + ".json");

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(file).as("%s is missing. %s", file, UPDATE_HINT).exists();
        assertThat(JSON.readTree(generated))
                .as("%s differs from the code. %s", file, UPDATE_HINT)
                .isEqualTo(JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)));
    }
}
