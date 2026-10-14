package ua.edu.ukma.springers.voltstore.inventory.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ua.edu.ukma.springers.voltstore.inventory.dto.ReserveStockItem;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class UniqueProductIdsValidator implements ConstraintValidator<UniqueProductIds, List<ReserveStockItem>> {

    @Override
    public boolean isValid(List<ReserveStockItem> items, ConstraintValidatorContext context) {
        if (items == null) {
            return true;
        }
        Set<UUID> seen = new HashSet<>();
        return items.stream()
                .filter(Objects::nonNull)
                .map(ReserveStockItem::productId)
                .filter(Objects::nonNull)
                .allMatch(seen::add);
    }
}
