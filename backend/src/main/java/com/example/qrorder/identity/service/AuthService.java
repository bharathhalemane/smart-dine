package com.example.qrorder.identity.service;

import com.example.qrorder.identity.dto.LoginResponse;
import com.example.qrorder.identity.dto.LoginRequest;
import com.example.qrorder.identity.dto.RegisterRequest;
import com.example.qrorder.identity.dto.UserResponse;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.identity.config.JwtProperties;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService{

    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String EMAIL_TAKEN = "An account with this email already exists";
    private static final String BAD_CREDENTIALS = "Invalid email or password";
    private static final int MAX_FAILED_LOGINS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProperties,
                       Clock clock
    ){
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-qualizer-not-a-real-password");
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

    public LoginResponse login(LoginRequest request){
        String email = User.normalizeEmail(request.email());
        Instant now = clock.instant();
        Optional<User> found = users.findByEmail(email);

        String hash = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);

        if(found.isEmpty()){
            throw badCredentials();
        }

        User user = found.get();

        boolean locked = user.getLockedUntil() != null && now.isBefore(user.getLockedUntil());
        if(user.getStatus() != UserStatus.ACTIVE || locked){
            throw badCredentials();
        }

        if(!passwordOk){
            recordFailure(user, now);
            throw badCredentials();
        }

        if(user.getFailedLoginCount() > 0 || user.getLockedUntil() != null){
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            users.save(user);
        }

        String token = jwtService.issueAccessToken(user);
        return new LoginResponse(token, "Bearer", jwtProperties.accessTokenTtl().toSeconds(), UserResponse.from(user));
    }

    private void recordFailure(User user, Instant now){
        boolean lockExpired = user.getLockedUntil() != null && !now.isBefore(user.getLockedUntil());
        int failures = (lockExpired ? 0: user.getFailedLoginCount()) + 1;
        user.setFailedLoginCount(failures);
        user.setLockedUntil(failures >= MAX_FAILED_LOGINS ? now.plus(LOCK_DURATION) : null);
        try{
            users.save(user);
        }catch (OptimisticLockingFailureException ignored){

        }
    }

    private static DomainException badCredentials(){
        return new DomainException(ErrorCode.UNAUTHENTICATED, BAD_CREDENTIALS);
    }
}