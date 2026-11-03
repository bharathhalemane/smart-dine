package com.example.qrorder.identity.dto;

import com.example.qrorder.identity.model.User;

public record UserResponse(String id, String email, String name, boolean emailVerified){
    public static UserResponse from(User user){
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.isEmailVerified());
    }
}