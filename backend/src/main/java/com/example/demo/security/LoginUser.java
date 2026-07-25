package com.example.demo.security;

public record LoginUser(
        Long userId,
        String username,
        String role
) {
}