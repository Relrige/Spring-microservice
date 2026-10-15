package ua.edu.ukma.springers.voltstore.apigateway.routing;

public enum AuthMode {
    /** No token needed; any token is ignored and the request is forwarded as anonymous. */
    PUBLIC,
    /** Token used if present (an invalid token is rejected); anonymous otherwise. */
    OPTIONAL,
    /** Valid token required; role is checked if roles are listed. */
    REQUIRED
}
