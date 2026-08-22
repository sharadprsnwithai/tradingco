package com.tradingbot.adapter.shoonya;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoonyaAuthenticatorTest {

    private static final File SESSION_FILE = new File("data/shoonya_session.json");

    private ShoonyaConfig config;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        config = new ShoonyaConfig();
        objectMapper = new ObjectMapper();
        deleteSessionFile();
    }

    @AfterEach
    void tearDown() {
        deleteSessionFile();
    }

    private void deleteSessionFile() {
        if (SESSION_FILE.exists()) {
            SESSION_FILE.delete();
        }
    }

    @Test
    void testDisabledAdapterGeneratesMockToken() {
        config.setEnabled(false);
        config.setUserId("USER123");

        ShoonyaAuthenticator authenticator = new ShoonyaAuthenticator(config, WebClient.builder(), objectMapper);

        StepVerifier.create(authenticator.authenticate())
            .expectNext("mock_shoonya_access_token_USER123")
            .verifyComplete();

        assertTrue(authenticator.hasValidSession());
        assertEquals("mock_shoonya_access_token_USER123", authenticator.getAccessToken().block());
    }

    @Test
    void testExplicitAccessTokenConfigured() {
        config.setEnabled(true);
        config.setUserId("FA99999");
        config.setAccessToken("explicit_custom_token_12345");

        ShoonyaAuthenticator authenticator = new ShoonyaAuthenticator(config, WebClient.builder(), objectMapper);

        StepVerifier.create(authenticator.authenticate())
            .expectNext("explicit_custom_token_12345")
            .verifyComplete();

        assertTrue(authenticator.hasValidSession());
        assertEquals("explicit_custom_token_12345", authenticator.getSUserToken());
    }

    @Test
    void testLoadFreshDiskSession() throws Exception {
        config.setEnabled(true);
        config.setUserId("FA55555");
        config.setAccessToken(null);

        // Pre-create disk session
        SESSION_FILE.getParentFile().mkdirs();
        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", "disk_access_token_abc");
        data.put("susertoken", "disk_user_token_xyz");
        data.put("uid", "FA55555");
        data.put("actid", "FA55555_ACT");
        data.put("createdAt", Instant.now().toString());
        objectMapper.writeValue(SESSION_FILE, data);

        ShoonyaAuthenticator authenticator = new ShoonyaAuthenticator(config, WebClient.builder(), objectMapper);

        StepVerifier.create(authenticator.authenticate())
            .expectNext("disk_access_token_abc")
            .verifyComplete();

        assertTrue(authenticator.hasValidSession());
        assertEquals("disk_user_token_xyz", authenticator.getSUserToken());
    }

    @Test
    void testSetAccessTokenUpdatesBothTokens() {
        config.setEnabled(true);
        ShoonyaAuthenticator authenticator = new ShoonyaAuthenticator(config, WebClient.builder(), objectMapper);

        authenticator.setAccessToken("manual_token_999");
        assertTrue(authenticator.hasValidSession());
        assertEquals("manual_token_999", authenticator.getAccessToken().block());
        assertEquals("manual_token_999", authenticator.getSUserToken());
    }
}
