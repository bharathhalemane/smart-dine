package com.example.qrorder.common.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final AppMailProperties props;

    public SmtpEmailSender(JavaMailSender mailSender, AppMailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}