package ua.edu.ukma.springers.voltstore.authservice.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.authservice.dto.CreateNonCustomerUserRequest;
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

    // Only an administrator may call this; the rule lives in SecurityConfig (route-level role rule)
    @PostMapping("/non-customer")
    @ResponseStatus(code = HttpStatus.CREATED)
    public RegisterUserResponse createNonCustomerUser(@RequestBody @Valid CreateNonCustomerUserRequest request) {
        return userService.createNonCustomerUser(request);
    }
}
