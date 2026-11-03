package com.example.qrorder.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, message = "must be at least 10 characters") String password,
        @NotBlank @Size(max=100) String name){

}
