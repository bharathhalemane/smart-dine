package com.example.qrorder.common.mail;

/** Abstraction so modules don't depend on SMTP, and tests can replace it. */
public interface EmailSender {

    void send(String to, String subject, String body);
}