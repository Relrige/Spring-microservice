package ua.edu.ukma.springers.voltstore.order.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class UniqueProductIdsValidator implements ConstraintValidator<UniqueProductIds, List<OrderItemRequest>> {

    @Override
    public boolean isValid(List<OrderItemRequest> items, ConstraintValidatorContext context) {
        if (items == null) {
            return true;
        }
        Set<UUID> seen = new HashSet<>();
        return items.stream()
                .filter(Objects::nonNull)
                .map(OrderItemRequest::productId)
                .filter(Objects::nonNull)
                .allMatch(seen::add);
    }
}
