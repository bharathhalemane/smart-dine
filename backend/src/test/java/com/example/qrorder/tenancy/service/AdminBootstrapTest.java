package com.example.qrorder.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.identity.config.BootstrapProperties;
import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock UserRepository users;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // cheap for tests

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(users, encoder, new BootstrapProperties(email, password, "Platform Admin"));
    }

    @Test
    void doesNothingWhenNotConfigured() {
        bootstrap("", "").run(null);

        verifyNoInteractions(users);
    }

    @Test
    void createsAVerifiedPlatformAdminWhenTheAccountIsMissing() {
        when(users.existsByEmail("admin@example.com")).thenReturn(false);

        bootstrap("Admin@Example.com", "change-me-please-123").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getEmail()).isEqualTo("admin@example.com");
        assertThat(admin.getPlatformRole()).isEqualTo(PlatformRole.PLATFORM_ADMIN);
        assertThat(admin.isEmailVerified()).isTrue();
        assertThat(encoder.matches("change-me-please-123", admin.getPasswordHash())).isTrue();
    }

    @Test
    void neverTouchesAnExistingAccount() {
        when(users.existsByEmail("admin@example.com")).thenReturn(true);

        bootstrap("admin@example.com", "change-me-please-123").run(null);

        verify(users, never()).save(any());
    }

    @Test
    void refusesToStartWithAWeakConfiguredPassword() {
        when(users.existsByEmail("admin@example.com")).thenReturn(false);

        assertThatThrownBy(() -> bootstrap("admin@example.com", "short").run(null))
                .isInstanceOf(DomainException.class);
        verify(users, never()).save(any());
    }
}