package ua.edu.ukma.springers.voltstore.customer.dto;

import ua.edu.ukma.springers.voltstore.customer.entities.CustomerProfile;

public record ProfileResponse(String firstName, String lastName, String phone, String defaultAddress) {

    /** A customer who never saved a profile: all fields null, so the client shows an empty form (not an error). */
    public static ProfileResponse empty() {
        return new ProfileResponse(null, null, null, null);
    }

    public static ProfileResponse from(CustomerProfile profile) {
        return new ProfileResponse(profile.getFirstName(), profile.getLastName(), profile.getPhone(),
                profile.getDefaultAddress());
    }
}
