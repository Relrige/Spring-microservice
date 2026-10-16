package ua.edu.ukma.springers.voltstore.customer.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.customer.dto.ProfileRequest;
import ua.edu.ukma.springers.voltstore.customer.dto.ProfileResponse;
import ua.edu.ukma.springers.voltstore.customer.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.customer.services.CustomerProfileService;

// Who may call which route is declared in SecurityConfig; the customer is always the caller (X-User-Id)
@RestController
@RequestMapping("/customers/me/profile")
@RequiredArgsConstructor
public class CustomerProfileController {
    private final CustomerProfileService profileService;

    /** EP-CUST-01 */
    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedUser customer) {
        return profileService.getProfile(customer.id());
    }

    /** EP-CUST-02 */
    @PutMapping
    public ProfileResponse saveProfile(@AuthenticationPrincipal AuthenticatedUser customer,
                                       @Valid @RequestBody ProfileRequest request) {
        return profileService.saveProfile(customer.id(), request);
    }
}
