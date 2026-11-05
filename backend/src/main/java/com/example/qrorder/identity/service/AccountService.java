package com.example.qrorder.identity.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Public API of the identity module for other modules. */
@Service
public class AccountService {

    private static final String EMAIL_TAKEN = "An account with this email already exists";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AccountService(UserRepository users, PasswordEncoder passwordEncoder)
    {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean isEmailVerified(String userId) {
        return users.findById(userId).map(User::isEmailVerified).orElse(false);
    }

    public Optional<String> findUserIdByEmail(String email) {
        return users.findByEmail(User.normalizeEmail(email)).map(User::getId);
    }

    public Optional<String> emailOf(String userId){
        return users.findById(userId).map(User::getEmail);
    }

    public String displayName(String userId) {
        return users.findById(userId).map(User::getName).orElse("A colleague");
    }

    public String createVerifiedAccount(String email, String name, String rawPassword){
        PasswordPolicy.requireAcceptable(rawPassword);

        String normalized = User.normalizeEmail(email);
        if(users.existsByEmail(normalized)){
            throw DomainException.conflict(EMAIL_TAKEN);
        }

        User user = new User(normalized,passwordEncoder.encode(rawPassword), name.trim());
        user.setEmailVerified(true);
        try{
            return users.save(user).getId();
        }catch(DuplicateKeyException e){
            throw DomainException.conflict(EMAIL_TAKEN);
        }
    }
}