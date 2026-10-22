package ua.edu.ukma.springers.voltstore.authservice.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The OpenAPI document of this service (docs/design/services-requirements/openapi-conventions.md). It describes the
 * API as a client sees it through the API Gateway: gateway paths and a bearer JWT; the trusted {@code X-User-*}
 * headers never appear (D7).
 * <p>
 * One group (D4), served at {@code /v3/api-docs/public} and committed as {@code openapi/public.json} by
 * {@code OpenApiDocumentTest}. This service has no internal endpoints, so there is no {@code internal} group and no
 * {@code @InternalApi}.
 * <p>
 * Copied from the Catalog pilot, like the {@code security} package: only the title, the problem schemas and the
 * groups differ between services.
 */
@Configuration
public class OpenApiConfig {
    /** Name of the bearer scheme; operations that need a token reference it in {@code @SecurityRequirement}. */
    public static final String BEARER_AUTH = "bearerAuth";

    // Referenced by ErrorResponsesCustomizer, so they must be in the document even where no annotation names them
    private static final List<Class<?>> SHARED_PROBLEMS = List.of(Problem.class, ValidationProblem.class);

    @Bean
    public OpenAPI openApi() {
        // Fixed title, version and server keep the committed snapshot stable (D3); springdoc would otherwise
        // write the request URL into servers
        return new OpenAPI()
                .info(new Info().title("Auth API").version("1"))
                .servers(List.of(new Server().url("/").description("Through the API Gateway")));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/**")
                .addOperationCustomizer(new ErrorResponsesCustomizer())
                .addOpenApiCustomizer(openApi -> components(openApi).addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("JWT from POST /auth/login")))
                .addOpenApiCustomizer(sharedProblems())
                .build();
    }

    private static OpenApiCustomizer sharedProblems() {
        return openApi -> SHARED_PROBLEMS.forEach(problem -> ModelConverters.getInstance(true)
                .readAll(new AnnotatedType(problem))
                .forEach(components(openApi)::addSchemas));
    }

    private static Components components(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        return openApi.getComponents();
    }
}
