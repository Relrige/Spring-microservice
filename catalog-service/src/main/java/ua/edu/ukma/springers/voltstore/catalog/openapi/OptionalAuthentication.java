package ua.edu.ukma.springers.voltstore.catalog.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documentation only: the gateway routes this operation as {@code OPTIONAL}, so it is documented with
 * {@code security: [{bearerAuth: []}, {}]}, "with a token or without one" (conventions D7). Springdoc drops an empty
 * {@code @SecurityRequirement}, so the public group sets this pair itself. Used by Catalog's product listing and
 * product details.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OptionalAuthentication {
}
