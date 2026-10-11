package ua.edu.ukma.springers.voltstore.catalog.security;

// Platform roles as forwarded by the API Gateway in X-User-Role (owned by auth-service; Catalog stores no users)
public enum UserRole {
    CUSTOMER,
    CATALOG_MANAGER,
    INVENTORY_WORKER,
    ADMIN
}
