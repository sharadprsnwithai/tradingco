package com.tradingbot.adapter.kite;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "trading-bot.brokers.kite")
public class KiteConfig {

    private boolean enabled = true;
    private String apiKey = "mock_kite_api_key";
    private String apiSecret = "mock_kite_secret";
    private String userId = "mock_kite_user";
    private String password = "mock_kite_password";
    private String totpSecret = "JBSWY3DPEHPK3PXP";
    private String baseUrl = "https://api.kite.trade";

    /**
     * Checks if the Kite adapter is enabled.
     *
     * @return true if the adapter is enabled, false otherwise
     */
    public boolean isEnabled() { return enabled; }

    /**
     * Sets whether the Kite adapter is enabled.
     *
     * @param enabled true to enable the adapter, false to disable
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Returns the Kite API key.
     *
     * @return the API key string
     */
    public String getApiKey() { return apiKey; }

    /**
     * Sets the Kite API key.
     *
     * @param apiKey the API key string
     */
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    /**
     * Returns the Kite API secret.
     *
     * @return the API secret string
     */
    public String getApiSecret() { return apiSecret; }

    /**
     * Sets the Kite API secret.
     *
     * @param apiSecret the API secret string
     */
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }

    /**
     * Returns the Zerodha user ID.
     *
     * @return the user ID string
     */
    public String getUserId() { return userId; }

    /**
     * Sets the Zerodha user ID.
     *
     * @param userId the user ID string
     */
    public void setUserId(String userId) { this.userId = userId; }

    /**
     * Returns the Zerodha account password.
     *
     * @return the password string
     */
    public String getPassword() { return password; }

    /**
     * Sets the Zerodha account password.
     *
     * @param password the password string
     */
    public void setPassword(String password) { this.password = password; }

    /**
     * Returns the TOTP secret key for 2FA authentication.
     *
     * @return the TOTP secret string
     */
    public String getTotpSecret() { return totpSecret; }

    /**
     * Sets the TOTP secret key for 2FA authentication.
     *
     * @param totpSecret the TOTP secret string
     */
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }

    /**
     * Returns the base URL for Kite API endpoints.
     *
     * @return the base URL string
     */
    public String getBaseUrl() { return baseUrl; }

    /**
     * Sets the base URL for Kite API endpoints.
     *
     * @param baseUrl the base URL string
     */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
