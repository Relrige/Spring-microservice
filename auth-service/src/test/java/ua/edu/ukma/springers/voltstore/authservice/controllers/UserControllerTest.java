package ua.edu.ukma.springers.voltstore.authservice.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserResponse;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.EmailNotUniqueException;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.InvalidUserRoleException;
import ua.edu.ukma.springers.voltstore.authservice.services.UserService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {
    private static final String URL = "/user/register";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void register_validRequest_returns201WithUserId() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.registerUser(any())).thenReturn(new RegisterUserResponse(id));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(id.toString()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void register_invalidEmailAndShortPassword_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").isNotEmpty())
                .andExpect(jsonPath("$.errors.password").isNotEmpty());

        verify(userService, never()).registerUser(any());
    }

    @Test
    void register_missingFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").isNotEmpty())
                .andExpect(jsonPath("$.errors.password").isNotEmpty());
    }

    @Test
    void register_emptyEmail_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"secret1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").isNotEmpty())
                .andExpect(jsonPath("$.errors.password").doesNotExist());
    }

    @Test
    void register_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_emailAlreadyInUse_returns409() throws Exception {
        when(userService.registerUser(any())).thenThrow(new EmailNotUniqueException());

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Email already in use"));
    }

    @Test
    void register_unexpectedError_returns500WithoutInternals() throws Exception {
        when(userService.registerUser(any())).thenThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.service").value("auth-service"));
    }

    private static final String NON_CUSTOMER_URL = "/user/non-customer";
    private static final String NON_CUSTOMER_BODY =
            "{\"email\":\"manager@example.com\",\"password\":\"secret1\",\"role\":\"CATALOG_MANAGER\"}";

    @Test
    void createNonCustomer_asAdmin_returns201WithUserId() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.createNonCustomerUser(any())).thenReturn(new RegisterUserResponse(id));

        mockMvc.perform(post(NON_CUSTOMER_URL).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(NON_CUSTOMER_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(id.toString()));
    }

    @Test
    void createNonCustomer_withoutRoleHeader_returns403() throws Exception {
        mockMvc.perform(post(NON_CUSTOMER_URL).contentType(MediaType.APPLICATION_JSON).content(NON_CUSTOMER_BODY))
                .andExpect(status().isForbidden());

        verify(userService, never()).createNonCustomerUser(any());
    }

    @Test
    void createNonCustomer_asNonAdminRole_returns403() throws Exception {
        for (String role : new String[]{"CUSTOMER", "CATALOG_MANAGER", "INVENTORY_WORKER", "garbage"}) {
            mockMvc.perform(post(NON_CUSTOMER_URL).header("X-User-Role", role)
                            .contentType(MediaType.APPLICATION_JSON).content(NON_CUSTOMER_BODY))
                    .andExpect(status().isForbidden());
        }

        verify(userService, never()).createNonCustomerUser(any());
    }

    @Test
    void createNonCustomer_nonAdminWithInvalidBody_returns403Not400() throws Exception {
        mockMvc.perform(post(NON_CUSTOMER_URL).header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createNonCustomer_missingRole_returns400WithFieldError() throws Exception {
        mockMvc.perform(post(NON_CUSTOMER_URL).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").isNotEmpty());
    }

    @Test
    void createNonCustomer_unknownRole_returns400() throws Exception {
        mockMvc.perform(post(NON_CUSTOMER_URL).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"m@example.com\",\"password\":\"secret1\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNonCustomer_customerRole_returns400WithRoleError() throws Exception {
        when(userService.createNonCustomerUser(any())).thenThrow(new InvalidUserRoleException("not allowed"));

        mockMvc.perform(post(NON_CUSTOMER_URL).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"c@example.com\",\"password\":\"secret1\",\"role\":\"CUSTOMER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role[0]").value("not allowed"));
    }

    @Test
    void createNonCustomer_emailAlreadyInUse_returns409() throws Exception {
        when(userService.createNonCustomerUser(any())).thenThrow(new EmailNotUniqueException());

        mockMvc.perform(post(NON_CUSTOMER_URL).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(NON_CUSTOMER_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void register_isPublicAndNotAffectedByRoleHeaderCheck() throws Exception {
        when(userService.registerUser(any())).thenReturn(new RegisterUserResponse(UUID.randomUUID()));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"secret1\"}"))
                .andExpect(status().isCreated());
    }
}
