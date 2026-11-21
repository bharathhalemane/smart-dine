package com.example.qrorder.billing.provider;

import com.example.qrorder.common.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts provider secrets at rest (AES-256-GCM). The key comes from the environment, never from the database. */
@Component
public class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String encodedKey;

    public SecretBox(@Value("${app.payments.encryption-key:}") String encodedKey) {
        this.encodedKey = encodedKey;
    }

    public boolean configured() {
        return encodedKey != null && !encodedKey.isBlank();
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt: wrong encryption key?", e);
        }
    }

    private SecretKeySpec key() {
        if (!configured()) {
            throw DomainException.conflict("Online payments are not set up on this server");
        }
        byte[] bytes = Base64.getDecoder().decode(encodedKey.strip());
        if (bytes.length != 32) {
            throw new IllegalStateException("app.payments.encryption-key must be base64 of exactly 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}