package com.example.qrorder.identity.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AuthService;
import com.example.qrorder.identity.service.AuthResult;
import com.example.qrorder.identity.service.EmailVerificationService;
import com.example.qrorder.identity.service.PasswordResetService;
import com.example.qrorder.identity.dto.RegisterRequest;
import com.example.qrorder.identity.dto.UserResponse;
import com.example.qrorder.identity.dto.LoginRequest;
import com.example.qrorder.identity.dto.LoginResponse;
import com.example.qrorder.identity.dto.VerifyEmailRequest;
import com.example.qrorder.identity.dto.ResendVerificationRequest;
import com.example.qrorder.identity.dto.ForgotPasswordRequest;
import com.example.qrorder.identity.dto.ResetPasswordRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController{

    private static final String GUARD_HEADER = "X-Requested-With";
    private static final String GUARD_VALUE = "XMLHttpRequest";

    private final AuthService authService;
    private final RefreshCookies cookies;
    private final EmailVerificationService emailVerification;
    private final PasswordResetService passwordReset;

    public AuthController(AuthService authService,
                          RefreshCookies cookies,
                          EmailVerificationService emailVerification,
                          PasswordResetService passwordReset
    ){
        this.authService = authService;
        this.cookies = cookies;
        this.emailVerification = emailVerification;
        this.passwordReset = passwordReset;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request){
        return UserResponse.from(authService.register(request));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse http) {
        AuthResult result = authService.login(request);
        http.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(result.refreshToken()).toString());
        return result.response();
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            @RequestHeader(name = GUARD_HEADER, required = false) String guard,
            HttpServletResponse http
    ){
        requireGuardHeader(guard);
        if(refreshToken == null || refreshToken.isBlank()) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in");
        }

        AuthResult result = authService.refresh(refreshToken);
        http.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(result.refreshToken()).toString());
        return result.response();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            @RequestHeader(name = GUARD_HEADER, required = false) String guard,
            HttpServletResponse http
    ){
        requireGuardHeader(guard);
        authService.logout(refreshToken);
        http.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request){
        emailVerification.verify(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest request){
        emailVerification.resend(request.email());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request){
        passwordReset.requestReset(request.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request){
        passwordReset.reset(request.token(), request.newPassword());
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication){
        return authService.currentUser(authentication.getName()); // the name is the userId
    }

    private static void requireGuardHeader(String value){
        if(!GUARD_VALUE.equals(value)){
            throw new DomainException(ErrorCode.FORBIDDEN, "Missing required header");
        }
    }
}