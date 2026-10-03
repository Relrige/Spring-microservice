package ua.edu.ukma.springers.voltstore.order.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.util.UUID;

public interface CatalogClient {

    @GetExchange("/{id}")
    ProductDto getById(@PathVariable UUID id);

    @GetExchange("/{id}/slow")
    ProductDto getByIdSlow(@PathVariable UUID id);
}