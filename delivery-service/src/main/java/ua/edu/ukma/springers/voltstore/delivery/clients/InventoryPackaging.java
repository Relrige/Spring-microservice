package ua.edu.ukma.springers.voltstore.delivery.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Inventory's packaging answer (EP-INV-05). Unknown fields are ignored (tolerant reader). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryPackaging(int grossWeightGrams, int lengthCm, int widthCm, int heightCm) {
}
