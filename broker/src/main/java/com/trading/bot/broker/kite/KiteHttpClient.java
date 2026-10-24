package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.error.AmbiguousOrderStateException;
import com.trading.bot.broker.api.error.ApiException;
import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.api.error.NetworkException;
import com.trading.bot.broker.api.error.RateLimitException;
import com.trading.bot.broker.config.KiteProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Low-level HTTP client for Kite Connect API providing timeouts, error mapping, and ambiguity
 * detection.
 */
@Component
public final class KiteHttpClient {

  private static final int MAX_UNPARSEABLE_CHARS = 200;
  private static final int HTTP_OK = 200;
  private static final int HTTP_UNAUTHORIZED = 401;
  private static final int HTTP_FORBIDDEN = 403;
  private static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final int HTTP_INTERNAL_SERVER_ERROR = 500;

  private final KiteProperties props;
  private final SessionDelegate delegate;
  private final RestClient readClient;
  private final RestClient mutationClient;
  private final ObjectMapper objectMapper;

  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration placeReadTimeout;

  public KiteHttpClient(
      KiteProperties props, SessionDelegate delegate, RestClient.Builder builder) {
    this.props = Objects.requireNonNull(props, "props must not be null");
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(builder, "builder must not be null");

    this.connectTimeout = props.http().connectTimeout();
    this.readTimeout = props.http().readTimeout();
    this.placeReadTimeout = props.http().placeReadTimeout();

    this.objectMapper =
        JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    this.readClient =
        builder.clone().baseUrl(props.baseUrl()).defaultHeader("X-Kite-Version", "3").build();

    this.mutationClient =
        builder.clone().baseUrl(props.baseUrl()).defaultHeader("X-Kite-Version", "3").build();
  }

  /**
   * Executes a GET request against Kite.
   *
   * @param path API endpoint path
   * @param responseType target DTO class
   * @param mutability read or mutation
   * @param <T> DTO type
   * @return parsed DTO
   */
  public <T> T get(String path, Class<T> responseType, Mutability mutability) {
    return executeWithAuth(
        token ->
            getClient(mutability)
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "token " + props.apiKey() + ":" + token)
                .retrieve()
                .toEntity(String.class),
        responseType,
        mutability,
        path,
        null,
        false);
  }

  /**
   * Executes a POST request with form-encoded data against Kite.
   *
   * @param path API endpoint path
   * @param form form parameters
   * @param responseType target DTO class
   * @param mutability read or mutation
   * @param <T> DTO type
   * @return parsed DTO
   */
  public <T> T post(
      String path, Map<String, String> form, Class<T> responseType, Mutability mutability) {
    final MultiValueMap<String, String> multiMap = new LinkedMultiValueMap<>();
    if (form != null) {
      form.forEach(multiMap::add);
    }

    return executeWithAuth(
        token ->
            getClient(mutability)
                .post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "token " + props.apiKey() + ":" + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(multiMap)
                .retrieve()
                .toEntity(String.class),
        responseType,
        mutability,
        path,
        form,
        false);
  }

  /**
   * Executes a PUT request with form-encoded data against Kite.
   *
   * @param path API endpoint path
   * @param form form parameters
   * @param responseType target DTO class
   * @param mutability read or mutation
   * @param <T> DTO type
   * @return parsed DTO
   */
  public <T> T put(
      String path, Map<String, String> form, Class<T> responseType, Mutability mutability) {
    final MultiValueMap<String, String> multiMap = new LinkedMultiValueMap<>();
    if (form != null) {
      form.forEach(multiMap::add);
    }

    return executeWithAuth(
        token ->
            getClient(mutability)
                .put()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "token " + props.apiKey() + ":" + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(multiMap)
                .retrieve()
                .toEntity(String.class),
        responseType,
        mutability,
        path,
        form,
        false);
  }

  /**
   * Executes a DELETE request against Kite.
   *
   * @param path API endpoint path
   * @param responseType target DTO class
   * @param mutability read or mutation
   * @param <T> DTO type
   * @return parsed DTO
   */
  public <T> T delete(String path, Class<T> responseType, Mutability mutability) {
    return executeWithAuth(
        token ->
            getClient(mutability)
                .delete()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "token " + props.apiKey() + ":" + token)
                .retrieve()
                .toEntity(String.class),
        responseType,
        mutability,
        path,
        null,
        false);
  }

  private RestClient getClient(Mutability mutability) {
    return mutability == Mutability.MUTATION ? mutationClient : readClient;
  }

  @FunctionalInterface
  private interface RequestFunction {
    ResponseEntity<String> execute(String token);
  }

  private <T> T executeWithAuth(
      RequestFunction requestFunction,
      Class<T> responseType,
      Mutability mutability,
      String path,
      Map<String, String> form,
      boolean isRetry) {
    final String token = delegate.accessToken();

    try {
      final ResponseEntity<String> response = requestFunction.execute(token);
      return parseBody(
          response.getBody(), responseType, mutability, path, extractCorrelationId(form));
    } catch (HttpStatusCodeException e) {
      final int status = e.getStatusCode().value();
      final String body = e.getResponseBodyAsString();

      if (status == HTTP_TOO_MANY_REQUESTS) {
        throw new RateLimitException("Rate limit exceeded on " + path + ": " + sanitize(body), e);
      }

      final boolean isTokenError = isTokenException(status, body);
      if (isTokenError && !isRetry && mutability == Mutability.READ) {
        delegate.forceRelogin();
        return executeWithAuth(requestFunction, responseType, mutability, path, form, true);
      }
      if (isTokenError) {
        throw new AuthException(extractMessage(body, "Authentication failed on " + path), e);
      }

      if (mutability == Mutability.MUTATION && status >= HTTP_INTERNAL_SERVER_ERROR) {
        final String correlationId = extractCorrelationId(form);
        throw new AmbiguousOrderStateException(
            path,
            correlationId,
            "Server error " + status + " on order mutation: " + sanitize(body),
            e);
      }

      if (isHtmlOrNonJson(body)) {
        throw new ApiException("UnparseableBody", status, truncateSanitized(body), e);
      }

      final String errorType = extractErrorType(body, "ApiException");
      final String message = extractMessage(body, "API request failed with HTTP " + status);
      throw new ApiException(errorType, status, message, e);
    } catch (ResourceAccessException e) {
      if (mutability == Mutability.MUTATION) {
        final String correlationId = extractCorrelationId(form);
        throw new AmbiguousOrderStateException(
            path,
            correlationId,
            "Network timeout or disconnect during mutation: " + sanitize(e.getMessage()),
            e);
      }
      throw new NetworkException(
          "Network failure accessing " + path + ": " + sanitize(e.getMessage()), e);
    } catch (RestClientException e) {
      if (mutability == Mutability.MUTATION) {
        final String correlationId = extractCorrelationId(form);
        throw new AmbiguousOrderStateException(
            path, correlationId, "Client error during mutation: " + sanitize(e.getMessage()), e);
      }
      throw new ApiException(
          "ClientError", HTTP_INTERNAL_SERVER_ERROR, sanitize(e.getMessage()), e);
    }
  }

  @SuppressWarnings("PMD.PreserveStackTrace")
  private <T> T parseBody(
      String body,
      Class<T> responseType,
      Mutability mutability,
      String path,
      String correlationId) {
    try {
      return objectMapper.readValue(body, responseType);
    } catch (JacksonException e) {
      // Spec §7: a truncated/unparseable 200 body on a mutation means the order may exist.
      if (mutability == Mutability.MUTATION) {
        throw new AmbiguousOrderStateException(
            path,
            correlationId,
            "Unparseable 200 response on order mutation: " + truncateSanitized(body),
            new IOException("Failed to parse JSON response body"));
      }
      throw new ApiException(
          "UnparseableBody",
          HTTP_OK,
          truncateSanitized(body),
          new IOException("Failed to parse JSON response body"));
    }
  }

  private boolean isTokenException(int status, String body) {
    if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
      return true;
    }
    if (body != null) {
      final String errType = extractErrorType(body, "");
      return errType.contains("Token") || errType.contains("Auth");
    }
    return false;
  }

  private boolean isHtmlOrNonJson(String body) {
    if (body == null || body.isBlank()) {
      return true;
    }
    final String trimmed = body.trim();
    if (trimmed.startsWith("<") || !trimmed.startsWith("{")) {
      return true;
    }
    try {
      objectMapper.readTree(trimmed);
      return false;
    } catch (JacksonException ignored) {
      return true;
    }
  }

  private String extractErrorType(String body, String fallback) {
    if (body == null || body.isBlank()) {
      return fallback;
    }
    try {
      final JsonNode root = objectMapper.readTree(body);
      final JsonNode typeNode = root.path("error_type");
      if (!typeNode.isMissingNode() && !typeNode.asText().isBlank()) {
        return typeNode.asText();
      }
    } catch (JacksonException ignored) {
      return fallback;
    }
    return fallback;
  }

  private String extractMessage(String body, String fallback) {
    if (body == null || body.isBlank()) {
      return fallback;
    }
    try {
      final JsonNode root = objectMapper.readTree(body);
      final JsonNode msgNode = root.path("message");
      if (!msgNode.isMissingNode() && !msgNode.asText().isBlank()) {
        return msgNode.asText();
      }
    } catch (JacksonException ignored) {
      return fallback;
    }
    return fallback;
  }

  private String extractCorrelationId(Map<String, String> form) {
    if (form != null) {
      if (form.containsKey("tag") && form.get("tag") != null) {
        return form.get("tag");
      }
      if (form.containsKey("order_id") && form.get("order_id") != null) {
        return form.get("order_id");
      }
    }
    return "";
  }

  private String truncateSanitized(String input) {
    if (input == null) {
      return "";
    }
    final String sanitized = sanitize(input);
    if (sanitized.length() > MAX_UNPARSEABLE_CHARS) {
      return sanitized.substring(0, MAX_UNPARSEABLE_CHARS);
    }
    return sanitized;
  }

  private String sanitize(String input) {
    if (input == null) {
      return "";
    }
    return input.replace(props.apiKey(), "[REDACTED]").replace(props.password(), "[REDACTED]");
  }
}
