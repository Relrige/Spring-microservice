package ua.edu.ukma.springers.voltstore.delivery.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The OpenAPI documents of this service (docs/design/services-requirements/openapi-conventions.md). They describe the
 * API as a client sees it through the API Gateway: gateway paths, a bearer JWT on public operations, the internal token
 * on internal ones; the trusted {@code X-User-*} headers never appear (D7).
 * <p>
 * Two groups (D4), served at {@code /v3/api-docs/public} and {@code /v3/api-docs/internal} and committed as
 * {@code openapi/public.json} and {@code openapi/internal.json} by {@code OpenApiDocumentTest}. A service without
 * internal endpoints keeps only the {@code public} group and drops {@link #INTERNAL_TOKEN}.
 * <p>
 * Copied from the Catalog pilot, like the {@code security} package: only the title, the problem schemas and
 * the groups differ between services.
 */
@Configuration
public class OpenApiConfig {
    /** Name of the bearer scheme; public operations reference it in {@code @SecurityRequirement}. */
    public static final String BEARER_AUTH = "bearerAuth";
    public static final String INTERNAL_TOKEN = "internalToken";

    // Referenced by ErrorResponsesCustomizer, so they must be in every group even where no annotation names them
    private static final List<Class<?>> SHARED_PROBLEMS = List.of(Problem.class, ValidationProblem.class);

    @Bean
    public OpenAPI openApi() {
        // Fixed title, version and server keep the committed snapshot stable (D3); springdoc would otherwise
        // write the request URL into servers
        return new OpenAPI()
                .info(new Info().title("Delivery API").version("1"))
                .servers(List.of(new Server().url("/").description("Through the API Gateway")));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/**")
                .addOpenApiMethodFilter(method -> !method.isAnnotationPresent(InternalApi.class))
                .addOperationCustomizer(new ErrorResponsesCustomizer())
                .addOpenApiCustomizer(openApi -> components(openApi).addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("JWT from POST /auth/login")))
                .addOpenApiCustomizer(sharedProblems())
                .build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
                .group("internal")
                .pathsToMatch("/**")
                .addOpenApiMethodFilter(method -> method.isAnnotationPresent(InternalApi.class))
                .addOperationCustomizer(new ErrorResponsesCustomizer())
                .addOpenApiCustomizer(openApi -> {
                    components(openApi).addSecuritySchemes(INTERNAL_TOKEN, new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Internal-Token")
                            .description("Shared service-to-service token"));
                    // Every internal operation requires the token, so it is set here rather than per method
                    openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                            operation.setSecurity(List.of(new SecurityRequirement().addList(INTERNAL_TOKEN)))));
                })
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
