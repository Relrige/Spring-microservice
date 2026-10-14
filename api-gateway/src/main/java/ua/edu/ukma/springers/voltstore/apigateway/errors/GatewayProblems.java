package ua.edu.ukma.springers.voltstore.apigateway.errors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.time.Instant;

/**
 * Errors produced by the gateway itself, in the RFC 9457 format the services use. The details are fixed strings:
 * a 401 never says why a token was rejected, and no error reveals upstream hosts.
 */
public final class GatewayProblems {
    public static final String SERVICE_NAME = "api-gateway";

    private GatewayProblems() {
    }

    public static ServerResponse unauthorized() {
        return response(problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Authentication required"));
    }

    public static ServerResponse forbidden() {
        return response(problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Access denied"));
    }

    public static ProblemDetail problem(HttpStatus status, String errorType, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://voltstore.com/errors/" + errorType));
        problem.setTitle(title);
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", SERVICE_NAME);
        return problem;
    }

    private static ServerResponse response(ProblemDetail problem) {
        return ServerResponse.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
