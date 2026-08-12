package com.videonest.security;

public record LoginUser(
        Long userId,
        String username,
        String role
) {
}