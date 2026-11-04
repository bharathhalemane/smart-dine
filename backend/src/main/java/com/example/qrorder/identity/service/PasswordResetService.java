package com.example.qrorder.identity.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.mail.EmailSender;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.config.PasswordResetProperties;
import com.example.qrorder.identity.model.PasswordResetToken;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.repository.PasswordResetTokenRepository;
import com.example.qrorder.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int MAX_SAVE_ATTEMPTS = 3;

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final RefreshTokenService refreshTokens;
    private final MongoTemplate mongo;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final PasswordResetProperties props;
    private final Clock clock;

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                RefreshTokenService refreshTokens, MongoTemplate mongo,
                                PasswordEncoder passwordEncoder, EmailSender emailSender,
                                PasswordResetProperties props, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.mongo = mongo;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.props = props;
        this.clock = clock;
    }

    /** Looks identical from outside whether or not an email was sent. */
    public void requestReset(String rawEmail) {
        Instant now = clock.instant();
        users.findByEmail(User.normalizeEmail(rawEmail))
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> tokens.findFirstByUserIdOrderByCreatedAtDesc(u.getId())
                        .map(t -> t.getCreatedAt().plus(props.resendCooldown()).isBefore(now))
                        .orElse(true))
                .ifPresent(this::sendResetEmail);
    }

    public void reset(String plainToken, String newPassword) {
        PasswordPolicy.requireAcceptable(newPassword); // before touching the token, so a typo doesn't burn the link

        // Atomic consume: of two simultaneous requests with the same token, only one gets the document.
        Query live = Query.query(Criteria.where("tokenHash").is(Tokens.sha256(plainToken))
                .and("expiresAt").gt(clock.instant()));
        PasswordResetToken consumed = mongo.findAndRemove(live, PasswordResetToken.class);
        if (consumed == null) {
            throw invalid();
        }

        String newHash = passwordEncoder.encode(newPassword);
        String userId = consumed.getUserId();
        applyNewPassword(userId, newHash);

        tokens.deleteByUserId(userId);          // any other outstanding reset links die too
        refreshTokens.revokeAllForUser(userId); // sign out every device
    }

    private void applyNewPassword(String userId, String newHash) {
        for (int attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++) {
            User user = users.findById(userId)
                    .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                    .orElseThrow(PasswordResetService::invalid);
            user.setPasswordHash(newHash);
            user.setEmailVerified(true); // they just proved they control this mailbox
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            try {
                users.save(user);
                return;
            } catch (OptimisticLockingFailureException e) {
                // Another update won the race; reload and retry.
            }
        }
        throw DomainException.conflict("Could not reset the password, please request a new link");
    }

    private void sendResetEmail(User user) {
        String plain = Tokens.newToken();
        Instant now = clock.instant();

        tokens.deleteByUserId(user.getId()); // one live link per user
        tokens.save(new PasswordResetToken(Tokens.sha256(plain), user.getId(), now.plus(props.ttl()), now));

        String link = props.linkBaseUrl() + "/reset-password?token=" + plain;
        String body = """
                Hi %s,

                We received a request to reset your password. Choose a new one here:
                %s

                This link expires in %d minutes. If you didn't ask for this, ignore this email: your password stays the same.
                """.formatted(user.getName(), link, props.ttl().toMinutes());
        try {
            emailSender.send(user.getEmail(), "Reset your password", body);
        } catch (RuntimeException e) {
            log.warn("Could not send password reset email for userId={}: {}", user.getId(), e.getMessage());
        }
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid or expired reset link");
    }
}