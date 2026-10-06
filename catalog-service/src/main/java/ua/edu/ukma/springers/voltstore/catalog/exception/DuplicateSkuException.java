package ua.edu.ukma.springers.voltstore.catalog.exception;

public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String sku) {
        super("A product with SKU '" + sku + "' already exists.");
    }
}