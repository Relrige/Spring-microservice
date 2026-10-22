package ua.edu.ukma.springers.voltstore.inventory.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documentation only: puts a controller method into the {@code internal} OpenAPI group instead of {@code public}
 * (conventions D4). Every method behind a {@code hasRole("SERVICE")} rule in {@code SecurityConfig} carries it.
 * It has no effect on routing or security; {@code SecurityConfig} stays the place where access is enforced.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalApi {
}
