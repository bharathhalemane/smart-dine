package com.example.qrorder.common.security;

/** Lets the security layer ask "is this user still allowed in?" without depending on the identity module. */
public interface ActiveUserCheck {

    boolean isActive(String userId);
}