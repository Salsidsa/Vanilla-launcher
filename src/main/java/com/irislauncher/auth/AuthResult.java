package com.irislauncher.auth;

public class AuthResult {
    public final String accessToken;
    public final String refreshToken;
    public final String username;
    public final String uuid;

    public AuthResult(String accessToken, String refreshToken, String username, String uuid) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.username = username;
        this.uuid = uuid;
    }
}
