package ua.edu.ukma.springers.voltstore.authservice.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.authservice.dto.CreateNonCustomerUserRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserResponse;
import ua.edu.ukma.springers.voltstore.authservice.openapi.Problem;
import ua.edu.ukma.springers.voltstore.authservice.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.authservice.services.UserService;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.authservice.openapi.OpenApiConfig.BEARER_AUTH;

@Tag(name = "Users")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @Operation(summary = "Register as a customer (EP-AUTH-01)",
            description = "Public. Creates a user with role CUSTOMER.")
    @ApiResponse(responseCode = "201", description = "Registered")
    @ApiResponse(responseCode = "400", description = "Invalid fields",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "409", description = "The email is already registered",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/register")
    @ResponseStatus(code = HttpStatus.CREATED)
    public RegisterUserResponse registerUser(@RequestBody @Valid RegisterUserRequest request) {
        RegisterUserResponse response = userService.registerUser(request);
        return response;
    }

    // Only an administrator may call this; the rule lives in SecurityConfig (route-level role rule)
    @Operation(summary = "Create a staff user (EP-AUTH-03)",
            description = "Role: ADMIN. Creates a user with any role except CUSTOMER.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Invalid fields, including the role CUSTOMER or an unknown role",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not an ADMIN",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The email is already registered",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/non-customer")
    @ResponseStatus(code = HttpStatus.CREATED)
    public RegisterUserResponse createNonCustomerUser(@RequestBody @Valid CreateNonCustomerUserRequest request) {
        return userService.createNonCustomerUser(request);
    }
}
