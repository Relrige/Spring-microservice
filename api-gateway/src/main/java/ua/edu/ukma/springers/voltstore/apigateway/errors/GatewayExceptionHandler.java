package ua.edu.ukma.springers.voltstore.apigateway.errors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Turns exceptions raised while handling a request (no route, upstream unreachable or too slow, gateway bugs) into
 * the gateway's problem-details format. Upstream responses, including their 4xx/5xx, never pass through here: the
 * proxy returns them as normal responses.
 */
@RestControllerAdvice
public class GatewayExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ProblemDetail> handleUpstreamFailure(ResourceAccessException ex) {
        // The message names the upstream host; it is logged, never returned
        if (isReadTimeout(ex)) {
            log.warn("Upstream timed out: {}", ex.getMessage());
            return problem(GatewayProblems.problem(HttpStatus.GATEWAY_TIMEOUT, "gateway-timeout",
                    "Gateway Timeout", "The upstream service did not respond in time."));
        }
        log.warn("Upstream unavailable: {}", ex.getMessage());
        return problem(GatewayProblems.problem(HttpStatus.BAD_GATEWAY, "bad-gateway",
                "Bad Gateway", "The upstream service is unavailable."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAllOtherExceptions(Exception ex) {
        // The client only gets a generic message, so the real cause must be recorded here
        log.error("Unhandled exception", ex);
        return problem(GatewayProblems.problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal Server Error", "An unexpected error occurred."));
    }

    /**
     * Framework exceptions handled by the base class (most importantly "no route" → 404, never 405) get the same
     * fields as the gateway's own errors and a fixed detail.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == HttpStatus.NOT_FOUND || status == HttpStatus.METHOD_NOT_ALLOWED) {
            // An unlisted method is "no route", the same as an unlisted path
            ProblemDetail problem = GatewayProblems.problem(HttpStatus.NOT_FOUND, "not-found", "Not Found",
                    "No route matches the request.");
            HttpHeaders cleaned = new HttpHeaders();
            cleaned.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            return new ResponseEntity<>(problem, cleaned, HttpStatus.NOT_FOUND);
        }
        if (body instanceof ProblemDetail problem && status != null) {
            ProblemDetail decorated = GatewayProblems.problem(status, "request",
                    status.getReasonPhrase(), problem.getDetail() == null ? status.getReasonPhrase() : problem.getDetail());
            return super.handleExceptionInternal(ex, decorated, headers, statusCode, request);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static boolean isReadTimeout(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return false; // could not connect at all: unavailable, not slow
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
