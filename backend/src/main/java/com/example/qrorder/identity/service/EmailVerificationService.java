package com.example.qrorder.identity.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.mail.EmailSender;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.config.VerificationProperties;
import com.example.qrorder.identity.model.EmailVerificationToken;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.repository.EmailVerificationTokenRepository;
import com.example.qrorder.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int MAX_SAVE_ATTEMPTS = 3;

    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final EmailSender emailSender;
    private final VerificationProperties props;
    private final Clock clock;

    public EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens,
                                    EmailSender emailSender, VerificationProperties props, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.emailSender = emailSender;
        this.props = props;
        this.clock = clock;
    }

    /** Creates a fresh token (replacing any older one) and emails the link. Never throws on mail failure. */
    public void sendVerification(User user) {
        String plain = Tokens.newToken();
        Instant now = clock.instant();

        tokens.deleteByUserId(user.getId()); // one live token per user
        tokens.save(new EmailVerificationToken(Tokens.sha256(plain), user.getId(), now.plus(props.ttl()), now));

        String link = props.linkBaseUrl() + "/verify-email?token=" + plain;
        String body = """
                Hi %s,

                Confirm your email address to start setting up your restaurant:
                %s

                This link expires in %d hours. If you didn't create an account, you can ignore this email.
                """.formatted(user.getName(), link, props.ttl().toHours());
        try {
            emailSender.send(user.getEmail(), "Confirm your email address", body);
        } catch (RuntimeException e) {
            // Registration must not fail because of the mail server; the user can request a new link.
            log.warn("Could not send verification email for userId={}: {}", user.getId(), e.getMessage());
        }
    }

    /** Public "resend" flow. Always looks the same from outside, whether or not an email was sent. */
    public void resend(String rawEmail) {
        Instant now = clock.instant();
        users.findByEmail(User.normalizeEmail(rawEmail))
                .filter(u -> !u.isEmailVerified() && u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> tokens.findFirstByUserIdOrderByCreatedAtDesc(u.getId())
                        .map(t -> t.getCreatedAt().plus(props.resendCooldown()).isBefore(now))
                        .orElse(true))
                .ifPresent(this::sendVerification);
    }

    public void verify(String plainToken) {
        Instant now = clock.instant();
        EmailVerificationToken token = tokens.findByTokenHash(Tokens.sha256(plainToken))
                .filter(t -> t.getExpiresAt().isAfter(now))
                .orElseThrow(EmailVerificationService::invalid);

        markVerified(token.getUserId());
        tokens.delete(token);
    }

    private void markVerified(String userId) {
        for (int attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++) {
            User user = users.findById(userId).orElseThrow(EmailVerificationService::invalid);
            if (user.isEmailVerified()) {
                return;
            }
            user.setEmailVerified(true);
            try {
                users.save(user);
                return;
            } catch (OptimisticLockingFailureException e) {
                // Another update (e.g. a login) won the race; reload and try again.
            }
        }
        throw DomainException.conflict("Could not complete verification, please try again");
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid or expired verification link");
    }
}