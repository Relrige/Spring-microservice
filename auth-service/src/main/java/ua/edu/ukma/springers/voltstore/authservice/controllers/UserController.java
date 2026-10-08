package ua.edu.ukma.springers.voltstore.authservice.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserResponse;
import ua.edu.ukma.springers.voltstore.authservice.services.UserService;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping("/register")
    @ResponseStatus(code = HttpStatus.CREATED)
    public RegisterUserResponse registerUser(@RequestBody @Valid RegisterUserRequest request) {
        RegisterUserResponse response = userService.registerUser(request);
        return response;
    }
}
