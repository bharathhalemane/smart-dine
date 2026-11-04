package com.example.qrorder.identity.repository;

import com.example.qrorder.identity.model.EmailVerificationToken;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmailVerificationTokenRepository extends MongoRepository<EmailVerificationToken, String> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    Optional<EmailVerificationToken> findFirstByUserIdOrderByCreatedAtDesc(String userId);

    void deleteByUserId(String userId);
}