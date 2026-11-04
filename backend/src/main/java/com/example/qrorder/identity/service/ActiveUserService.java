package com.example.qrorder.identity.service;

import com.example.qrorder.common.security.ActiveUserCheck;
import com.example.qrorder.identity.model.UserStatus;
import com.example.qrorder.identity.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class ActiveUserService implements ActiveUserCheck {

    private final UserRepository users;

    public ActiveUserService(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean isActive(String userId) {
        return users.findById(userId).map(u -> u.getStatus() == UserStatus.ACTIVE).orElse(false);
    }
}