package ua.edu.ukma.springers.voltstore.catalog.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// The part of Inventory's 422 problem body Catalog reads; everything else is ignored (tolerant reader)
@JsonIgnoreProperties(ignoreUnknown = true)
record ReadinessProblem(String reason) {
}
