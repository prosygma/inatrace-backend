package com.abelium.inatrace.components.agstack;

import com.abelium.inatrace.api.ApiStatus;
import com.abelium.inatrace.api.errors.ApiException;
import com.abelium.inatrace.components.agstack.api.ApiLoginErrorResponse;
import com.abelium.inatrace.components.agstack.api.ApiLoginRequest;
import com.abelium.inatrace.components.agstack.api.ApiLoginResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

@Service
public class AgStackClientTokenManager {
    private static final long TOKEN_REFRESH_SECONDS = 3 * 3600;

    @Value("${INATrace.agstack.email}")
    private String email;

    @Value("${INATrace.agstack.password}")
    private String password;

    @Value("${INATrace.agstack.loginBaseURL}")
    private String baseURL;

    private String token;
    private Instant lastUpdated;

    public String retrieveToken() {
        Instant now = Instant.now();

        if (shouldRefreshToken(now)) {
            refreshToken(now);
        }
        return token;
    }

    private void refreshToken(Instant instant) {
        this.token = this.login(email, password).getAccessToken();
        if (token != null) {
            this.lastUpdated = instant;
        }
    }

    private boolean shouldRefreshToken(Instant instant) {
        if (this.token == null || this.lastUpdated == null) {
            return true;
        }
        return Duration.between(this.lastUpdated, instant).toSeconds() > TOKEN_REFRESH_SECONDS;
    }

    private ApiLoginResponse login(String username, String password) {
        ApiLoginRequest apiLoginRequest = new ApiLoginRequest();
        apiLoginRequest.setEmail(username);
        apiLoginRequest.setPassword(password);

        WebClient webClient = WebClient.create(baseURL);
        return webClient
                .post()
                .uri(uriBuilder -> uriBuilder.path("/login").build())
                .body(Mono.just(apiLoginRequest), ApiLoginRequest.class)
                .header("User-Agent", "Postman") // Fix for API to return JSON
                .header("X-FROM-ASSET-REGISTRY", "True")
                .header("Content-Type", "application/json")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(
                        HttpStatus.INTERNAL_SERVER_ERROR::equals,
                        clientResponse -> clientResponse
                                .bodyToMono(ApiLoginErrorResponse.class)
                                .flatMap(error -> Mono.error(new ApiException(ApiStatus.ERROR, error.getMessage()))))
                .onStatus(HttpStatus.BAD_REQUEST::equals,
                        clientResponse -> clientResponse
                                .bodyToMono(ApiLoginErrorResponse.class)
                                .flatMap(error -> Mono.error(new ApiException(ApiStatus.INVALID_REQUEST, error.getMessage()))))
                .bodyToMono(ApiLoginResponse.class)
                .block();
    }
}
