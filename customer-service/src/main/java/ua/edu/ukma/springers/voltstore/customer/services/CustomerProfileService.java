package ua.edu.ukma.springers.voltstore.customer.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.customer.dto.ProfileRequest;
import ua.edu.ukma.springers.voltstore.customer.dto.ProfileResponse;
import ua.edu.ukma.springers.voltstore.customer.repositories.CustomerProfileRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerProfileService {
    private final CustomerProfileRepository repository;

    /** EP-CUST-01: never creates a row; a missing profile is an empty one. */
    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID customerId) {
        return repository.findById(customerId).map(ProfileResponse::from).orElseGet(ProfileResponse::empty);
    }

    /** EP-CUST-02: insert or update, then return what is stored. */
    @Transactional
    public ProfileResponse saveProfile(UUID customerId, ProfileRequest request) {
        repository.upsert(customerId, request.firstName(), request.lastName(), request.phone(),
                request.defaultAddress());
        return repository.findById(customerId).map(ProfileResponse::from).orElseThrow();
    }
}
