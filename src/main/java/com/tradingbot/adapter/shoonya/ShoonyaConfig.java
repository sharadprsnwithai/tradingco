package com.tradingbot.adapter.shoonya;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "trading-bot.brokers.shoonya")
public class ShoonyaConfig {

    private boolean enabled = true;
    private String clientId = "mock_shoonya_client";
    private String secretKey = "mock_shoonya_secret";
    private String userId = "mock_shoonya_user";
    private String accountId = "mock_shoonya_act";
    private String password = "mock_shoonya_password";
    private String totpSecret = "JBSWY3DPEHPK3PXP";
    private String vendorCode = "mock_vendor";
    private String apiKey = "mock_api_key";
    private String baseUrl = "https://api.shoonya.com/NorenWClientAPI";
    private String wsUrl = "wss://api.shoonya.com/NorenWSAPI/";
    private String authCode;
    private String accessToken;

    /**
     * Checks if the Shoonya adapter is enabled.
     *
     * @return true if the adapter is enabled, false otherwise
     */
    public boolean isEnabled() { return enabled; }

    /**
     * Sets whether the Shoonya adapter is enabled.
     *
     * @param enabled true to enable the adapter, false to disable
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Gets the client ID for OAuth authentication.
     *
     * @return the client ID string
     */
    public String getClientId() { return clientId; }

    /**
     * Sets the client ID for OAuth authentication.
     *
     * @param clientId the client ID to set
     */
    public void setClientId(String clientId) { this.clientId = clientId; }

    /**
     * Gets the secret key for OAuth authentication.
     *
     * @return the secret key string
     */
    public String getSecretKey() { return secretKey; }

    /**
     * Sets the secret key for OAuth authentication.
     *
     * @param secretKey the secret key to set
     */
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    /**
     * Gets the user ID for the Shoonya account.
     *
     * @return the user ID string
     */
    public String getUserId() { return userId; }

    /**
     * Sets the user ID for the Shoonya account.
     *
     * @param userId the user ID to set
     */
    public void setUserId(String userId) { this.userId = userId; }

    /**
     * Gets the account ID, falling back to user ID if not set.
     *
     * @return the account ID string, or user ID if account ID is null
     */
    public String getAccountId() { return accountId != null ? accountId : userId; }

    /**
     * Sets the account ID for the Shoonya account.
     *
     * @param accountId the account ID to set
     */
    public void setAccountId(String accountId) { this.accountId = accountId; }

    /**
     * Gets the password for authentication.
     *
     * @return the password string
     */
    public String getPassword() { return password; }

    /**
     * Sets the password for authentication.
     *
     * @param password the password to set
     */
    public void setPassword(String password) { this.password = password; }

    /**
     * Gets the TOTP secret for generating one-time passwords.
     *
     * @return the TOTP secret string
     */
    public String getTotpSecret() { return totpSecret; }

    /**
     * Sets the TOTP secret for generating one-time passwords.
     *
     * @param totpSecret the TOTP secret to set
     */
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }

    /**
     * Gets the vendor code for API access.
     *
     * @return the vendor code string
     */
    public String getVendorCode() { return vendorCode; }

    /**
     * Sets the vendor code for API access.
     *
     * @param vendorCode the vendor code to set
     */
    public void setVendorCode(String vendorCode) { this.vendorCode = vendorCode; }

    /**
     * Gets the API key for authentication.
     *
     * @return the API key string
     */
    public String getApiKey() { return apiKey; }

    /**
     * Sets the API key for authentication.
     *
     * @param apiKey the API key to set
     */
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    /**
     * Gets the base URL for the Shoonya REST API.
     *
     * @return the base URL string
     */
    public String getBaseUrl() { return baseUrl; }

    /**
     * Sets the base URL for the Shoonya REST API.
     *
     * @param baseUrl the base URL to set
     */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /**
     * Gets the WebSocket URL for market data streaming.
     *
     * @return the WebSocket URL string
     */
    public String getWsUrl() { return wsUrl; }

    /**
     * Sets the WebSocket URL for market data streaming.
     *
     * @param wsUrl the WebSocket URL to set
     */
    public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }

    /**
     * Gets the authorization code obtained during OAuth flow.
     *
     * @return the authorization code string, or null if not set
     */
    public String getAuthCode() { return authCode; }

    /**
     * Sets the authorization code obtained during OAuth flow.
     *
     * @param authCode the authorization code to set
     */
    public void setAuthCode(String authCode) { this.authCode = authCode; }

    /**
     * Gets the access token for API requests.
     *
     * @return the access token string, or null if not set
     */
    public String getAccessToken() { return accessToken; }

    /**
     * Sets the access token for API requests.
     *
     * @param accessToken the access token to set
     */
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
}
