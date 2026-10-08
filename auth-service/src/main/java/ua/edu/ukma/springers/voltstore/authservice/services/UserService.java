package ua.edu.ukma.springers.voltstore.authservice.services;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserResponse;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.EmailNotUniqueException;
import ua.edu.ukma.springers.voltstore.authservice.repositories.UserRepository;
import ua.edu.ukma.springers.voltstore.authservice.utils.EmailNormalizer;

@Service
@RequiredArgsConstructor
public class UserService {
    // Name PostgreSQL gives to the inline UNIQUE constraint on users.email (see V1 migration)
    private static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterUserResponse registerUser(RegisterUserRequest request) {
        // Emails are case-insensitive: normalize once so "A@x.com" and "a@x.com" are the same account
        String email = EmailNormalizer.normalize(request.getEmail());
        validateEmail(email);

        String passwordHash = passwordEncoder.encode(request.getPassword());

        UserEntity entity = UserEntity.builder()
                .email(email)
                .passwordHash(passwordHash)
                .role(UserRole.CUSTOMER)
                .build();

        try {
            // Flush inside the try block so a unique constraint violation surfaces here, not at commit time
            userRepo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // Race condition: another request registered the same email after our existsByEmail check
            if (isEmailUniqueViolation(e)) {
                throw new EmailNotUniqueException();
            }
            throw e;
        }
        return new RegisterUserResponse(entity.getId());
    }

    private boolean isEmailUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve
                    && EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private void validateEmail(String email) {
        if (userRepo.existsByEmail(email)) {
            throw new EmailNotUniqueException();
        }
    }
}
