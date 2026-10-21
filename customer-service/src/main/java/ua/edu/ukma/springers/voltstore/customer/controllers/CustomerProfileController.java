package ua.edu.ukma.springers.voltstore.customer.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import ua.edu.ukma.springers.voltstore.customer.openapi.Problem;
import ua.edu.ukma.springers.voltstore.customer.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.customer.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.customer.services.CustomerProfileService;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.customer.openapi.OpenApiConfig.BEARER_AUTH;

// Who may call which route is declared in SecurityConfig; the customer is always the caller (X-User-Id)
@Tag(name = "Profile")
@RestController
@RequestMapping("/customers/me/profile")
@RequiredArgsConstructor
public class CustomerProfileController {
    private final CustomerProfileService profileService;

    /** EP-CUST-01 */
    @Operation(summary = "Get my profile (EP-CUST-01)",
            description = "Role: CUSTOMER. A customer who never saved a profile gets every field `null`.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The profile")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedUser customer) {
        return profileService.getProfile(customer.id());
    }

    /** EP-CUST-02 */
    @Operation(summary = "Save my profile (EP-CUST-02)", description = "Role: CUSTOMER. Creates or replaces the profile.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The profile as stored")
    @ApiResponse(responseCode = "400", description = "Invalid fields",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PutMapping
    public ProfileResponse saveProfile(@AuthenticationPrincipal AuthenticatedUser customer,
                                       @Valid @RequestBody ProfileRequest request) {
        return profileService.saveProfile(customer.id(), request);
    }
}
