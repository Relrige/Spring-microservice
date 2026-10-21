package ua.edu.ukma.springers.voltstore.authservice.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * Adds the error responses almost every operation can return (conventions D6), so controllers declare only their
 * operation-specific ones: {@code 400 ValidationProblem} when the operation takes parameters or a body, and
 * {@code 500 Problem} everywhere. A response already declared with {@code @ApiResponse} is never overwritten.
 */
public class ErrorResponsesCustomizer implements OperationCustomizer {
    private static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiResponses responses = operation.getResponses();
        boolean hasInput = (operation.getParameters() != null && !operation.getParameters().isEmpty())
                || operation.getRequestBody() != null;
        if (hasInput && !responses.containsKey("400")) {
            responses.addApiResponse("400", problem("Malformed request or invalid fields", ValidationProblem.class));
        }
        if (!responses.containsKey("500")) {
            responses.addApiResponse("500", problem("Unexpected error", Problem.class));
        }
        return operation;
    }

    private static ApiResponse problem(String description, Class<?> schema) {
        Schema<?> ref = new Schema<>().$ref("#/components/schemas/" + schema.getSimpleName());
        return new ApiResponse().description(description)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(ref)));
    }
}
