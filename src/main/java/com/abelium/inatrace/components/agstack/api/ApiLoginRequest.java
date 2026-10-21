package com.abelium.inatrace.components.agstack.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "API model for AgStack login request.")
public class ApiLoginRequest {
    private String email;
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
