package com.example.qrorder.identity;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService{

    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String EMAIL_TAKEN = "An account with this email already exists";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passowrdEncoder){
        this.users = users;
        this.passwordEncoder = passowrdEncoder;
    }

    public User register(RegisterRequest request){
        if(request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES){
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Password must be at most 72 bytes");
        }

        String email = User.normalizeEmail(request.email());
        if(users.existsByEmail(email)){
            throw DomainException.conflict(EMAIL_TAKEN);
        }
        
        User user = new User(email, passwordEncoder.encode(request.password()), request.name().trim());

        try{
            return users.save(user);
        }catch(DuplicateKeyException e){
            throw DomainException.conflict(EMAIL_TAKEN);
        }
    }
}