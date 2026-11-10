package com.example.qrorder.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CorsOriginsTest {

    @Test
    void readsTrimsLowercasesAndDropsDuplicatesAndBlanks() {
        assertThat(CorsOrigins.parse(" https://App.Example.com , http://localhost:5173,, https://app.example.com "))
                .containsExactly("https://app.example.com", "http://localhost:5173");
    }

    @Test
    void anEmptyListMeansNoCrossOriginAccess() {
        assertThat(CorsOrigins.parse("")).isEmpty();
        assertThat(CorsOrigins.parse(null)).isEmpty();
    }

    @Test
    void refusesEntriesThatWouldBeUnsafeOrWouldNeverMatch() {
        for (String bad : new String[] {"*", "https://*.example.com", "https://example.com/",
                "https://example.com/app", "example.com", "ftp://example.com", "http://user@example.com",
                "https://exa mple.com", "https://example.com:99999x"}) {
            assertThatThrownBy(() -> CorsOrigins.parse("https://ok.example.com," + bad))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid entry");
        }
    }
}