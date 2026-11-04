package com.example.qrorder.identity.repository;

import com.example.qrorder.identity.model.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PasswordResetTokenRepository extends MongoRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDesc(String userId);

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    void deleteByUserId(String userId);
}