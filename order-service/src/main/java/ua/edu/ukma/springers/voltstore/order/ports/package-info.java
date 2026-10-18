/**
 * What Order Service needs from Catalog, Inventory, Payment and Delivery, in Order's own terms. The business code
 * depends only on these interfaces; the HTTP adapters in {@code clients} implement them and translate status codes,
 * timeouts and open circuit breakers into these types or into
 * {@link ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException}.
 */
package ua.edu.ukma.springers.voltstore.order.ports;
