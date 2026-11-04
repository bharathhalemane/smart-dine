package com.example.qrorder.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.common.error.DomainException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsTenCharactersAndExactly72Bytes() {
        assertThatCode(() -> PasswordPolicy.requireAcceptable("abcdefghij")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.requireAcceptable("a".repeat(72))).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortPasswords() {
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable("abcdefghi"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void rejectsPasswordsOver72BytesEvenWhenFewCharacters() {
        // 25 euro signs = 25 characters but 75 bytes in UTF-8
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable("\u20ac".repeat(25)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("72 bytes");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PasswordPolicy.requireAcceptable(null))
                .isInstanceOf(DomainException.class);
    }
}