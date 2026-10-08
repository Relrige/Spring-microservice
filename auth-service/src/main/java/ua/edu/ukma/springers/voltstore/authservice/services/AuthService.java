package ua.edu.ukma.springers.voltstore.authservice.services;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.authservice.dto.LoginRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.LoginResponse;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.InvalidCredentialsException;
import ua.edu.ukma.springers.voltstore.authservice.repositories.UserRepository;
import ua.edu.ukma.springers.voltstore.authservice.security.TokenService;
import ua.edu.ukma.springers.voltstore.authservice.utils.EmailNormalizer;

@Service
public class AuthService {
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    // Verified against when the email is unknown, so both failure paths spend the same BCrypt time
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepo, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing-equalization");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = EmailNormalizer.normalize(request.getEmail());
        UserEntity user = userRepo.findByEmail(email).orElse(null);

        // Unknown email and wrong password must be indistinguishable to the caller (user enumeration defence)
        String hashToCheck = user != null ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToCheck);
        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return new LoginResponse(tokenService.issueToken(user));
    }
}
