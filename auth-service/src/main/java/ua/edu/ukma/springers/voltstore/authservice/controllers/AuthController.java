package ua.edu.ukma.springers.voltstore.authservice.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.authservice.dto.LoginRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.LoginResponse;
import ua.edu.ukma.springers.voltstore.authservice.openapi.Problem;
import ua.edu.ukma.springers.voltstore.authservice.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.authservice.services.AuthService;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @Operation(summary = "Log in (EP-AUTH-02)",
            description = "Public. Answers a JWT to send as `Authorization: Bearer <token>` on later calls.")
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "400", description = "Missing email or password",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Invalid credentials (unknown email or wrong password)",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Valid LoginRequest request) {
        return authService.login(request);
    }
}
