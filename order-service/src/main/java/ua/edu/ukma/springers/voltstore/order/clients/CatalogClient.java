package ua.edu.ukma.springers.voltstore.order.clients;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductDto;

import java.util.List;
import java.util.UUID;

public interface CatalogClient {
    @GetExchange("/products/batch")
    public List<ProductDto> getProductsByIds(@RequestParam List<UUID> ids);
}
