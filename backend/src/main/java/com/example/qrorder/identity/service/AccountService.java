package com.example.qrorder.identity.service;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** Public API of the identity module for other modules. */
@Service
public class AccountService {

    private final UserRepository users;

    public AccountService(UserRepository users) {
        this.users = users;
    }

    public boolean isEmailVerified(String userId) {
        return users.findById(userId).map(User::isEmailVerified).orElse(false);
    }

    public Optional<String> findUserIdByEmail(String email) {
        return users.findByEmail(User.normalizeEmail(email)).map(User::getId);
    }

    public String displayName(String userId) {
        return users.findById(userId).map(User::getName).orElse("A colleague");
    }
}