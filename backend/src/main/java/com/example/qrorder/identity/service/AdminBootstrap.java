package com.example.qrorder.identity.service;

import com.example.qrorder.identity.config.BootstrapProperties;
import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first platform admin from APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD, if both are set and no account
 * with that email exists. It never changes an existing account, so it cannot silently promote anyone.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapProperties props;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, BootstrapProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.adminEmail().isBlank() || props.adminPassword().isBlank()) {
            return;
        }
        String email = User.normalizeEmail(props.adminEmail());
        if (users.existsByEmail(email)) {
            log.info("Bootstrap admin skipped: an account with the configured email already exists");
            return;
        }
        PasswordPolicy.requireAcceptable(props.adminPassword()); // a weak configured password stops startup loudly

        User admin = new User(email, passwordEncoder.encode(props.adminPassword()), props.adminName().trim());
        admin.setEmailVerified(true);
        admin.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        try {
            users.save(admin);
            log.info("Bootstrap platform admin created");
        } catch (DuplicateKeyException e) {
            log.info("Bootstrap admin skipped: another instance created it first");
        }
    }
}