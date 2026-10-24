package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.trading.bot.broker.api.error.AmbiguousOrderStateException;
import com.trading.bot.broker.api.error.ApiException;
import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.api.error.RateLimitException;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.broker.kite.dto.OrderBookDto;
import com.trading.bot.broker.kite.dto.PlaceOrderResponseDto;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidDuplicateLiterals"})
class KiteHttpClientTest {

  private static final String API_KEY = "test-key";
  private static final String TOKEN = "active-token-xyz";
  private static final String BASE_URL = "https://api.kite.trade";

  private final KiteProperties props =
      new KiteProperties(API_KEY, "USR1", "pass", "TOTP1", BASE_URL, null, null, null);

  @Test
  void happyGetReturnsParsedDtoWithAuthHeaders() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final AtomicInteger relogins = new AtomicInteger(0);
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {
            relogins.incrementAndGet();
          }
        };

    server
        .expect(requestTo(BASE_URL + "/orders"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Kite-Version", "3"))
        .andExpect(header("Authorization", "token " + API_KEY + ":" + TOKEN))
        .andRespond(
            withSuccess("{\"status\":\"success\",\"data\":[]}", MediaType.APPLICATION_JSON));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);
    final OrderBookDto dto = client.get("/orders", OrderBookDto.class, Mutability.READ);

    server.verify();
    assertThat(dto).isNotNull();
    assertThat(dto.status()).isEqualTo("success");
    assertThat(relogins.get()).isEqualTo(0);
  }

  @Test
  void http429ThrowsRateLimitException() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    server
        .expect(requestTo(BASE_URL + "/orders"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);

    assertThatThrownBy(() -> client.get("/orders", OrderBookDto.class, Mutability.READ))
        .isInstanceOf(RateLimitException.class);
    server.verify();
  }

  @Test
  void tokenExceptionReplaysOnceThenSucceedsOrFails() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final AtomicInteger relogins = new AtomicInteger(0);
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {
            relogins.incrementAndGet();
          }
        };

    // First request fails with TokenException, second succeeds
    server
        .expect(requestTo(BASE_URL + "/orders"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"status\":\"error\",\"error_type\":\"TokenException\",\"message\":\"Expired token\"}"));
    server
        .expect(requestTo(BASE_URL + "/orders"))
        .andRespond(
            withSuccess("{\"status\":\"success\",\"data\":[]}", MediaType.APPLICATION_JSON));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);
    final OrderBookDto dto = client.get("/orders", OrderBookDto.class, Mutability.READ);

    server.verify();
    assertThat(dto).isNotNull();
    assertThat(relogins.get()).isEqualTo(1);

    // If second request also fails with TokenException -> AuthException thrown, exactly 2 requests
    // made
    final RestClient.Builder builder2 = RestClient.builder();
    final MockRestServiceServer server2 = MockRestServiceServer.bindTo(builder2).build();
    server2
        .expect(requestTo(BASE_URL + "/orders"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"status\":\"error\",\"error_type\":\"TokenException\",\"message\":\"Bad token\"}"));
    server2
        .expect(requestTo(BASE_URL + "/orders"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"status\":\"error\",\"error_type\":\"TokenException\",\"message\":\"Bad token again\"}"));

    final KiteHttpClient client2 = new KiteHttpClient(props, delegate, builder2);
    assertThatThrownBy(() -> client2.get("/orders", OrderBookDto.class, Mutability.READ))
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("Bad token");
    server2.verify();
  }

  @Test
  void mutationFailureThrowsAmbiguousOrderStateExceptionWithTag() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    server
        .expect(ExpectedCount.once(), requestTo(BASE_URL + "/orders/regular"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);

    final Map<String, String> form = Map.of("tag", "order-tag-xyz99", "quantity", "10");

    assertThatThrownBy(
            () ->
                client.post(
                    "/orders/regular", form, PlaceOrderResponseDto.class, Mutability.MUTATION))
        .isInstanceOf(AmbiguousOrderStateException.class)
        .satisfies(
            ex -> {
              final AmbiguousOrderStateException amb = (AmbiguousOrderStateException) ex;
              assertThat(amb.correlationId()).isEqualTo("order-tag-xyz99");
              assertThat(amb.operation()).isEqualTo("/orders/regular");
            });

    server.verify();
  }

  @Test
  void putSendsPutVerbWithFormBodyAndAuthHeaders() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    server
        .expect(requestTo(BASE_URL + "/orders/regular/2410230000001"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("X-Kite-Version", "3"))
        .andExpect(header("Authorization", "token " + API_KEY + ":" + TOKEN))
        .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);
    final Object result =
        client.put(
            "/orders/regular/2410230000001",
            Map.of("quantity", "20"),
            Object.class,
            Mutability.MUTATION);

    server.verify();
    assertThat(result).isNotNull();
  }

  @Test
  void deleteSendsDeleteVerbWithAuthHeaders() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    server
        .expect(requestTo(BASE_URL + "/orders/regular/2410230000002"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "token " + API_KEY + ":" + TOKEN))
        .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);
    client.delete("/orders/regular/2410230000002", Object.class, Mutability.MUTATION);

    server.verify();
  }

  @Test
  void putServerErrorThrowsAmbiguousOrderStateExceptionWithTag() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    server
        .expect(ExpectedCount.once(), requestTo(BASE_URL + "/orders/regular/2410230000003"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);

    assertThatThrownBy(
            () ->
                client.put(
                    "/orders/regular/2410230000003",
                    Map.of("tag", "modify-tag-1"),
                    Object.class,
                    Mutability.MUTATION))
        .isInstanceOf(AmbiguousOrderStateException.class)
        .satisfies(
            ex -> {
              final AmbiguousOrderStateException amb = (AmbiguousOrderStateException) ex;
              assertThat(amb.correlationId()).isEqualTo("modify-tag-1");
              assertThat(amb.operation()).isEqualTo("/orders/regular/2410230000003");
            });

    server.verify();
  }

  @Test
  void readFailureThrowsNetworkExceptionOrApiExceptionNeverAmbiguous() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    server
        .expect(requestTo(BASE_URL + "/orders"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("<html>500 Error</html>"));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);

    assertThatThrownBy(() -> client.get("/orders", OrderBookDto.class, Mutability.READ))
        .isInstanceOf(ApiException.class)
        .isNotInstanceOf(AmbiguousOrderStateException.class);

    server.verify();
  }

  @Test
  void html502BodyThrowsApiExceptionWithNoJacksonInCauseChain() {
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    final String html502 =
        "<html><head><title>502 Bad Gateway</title></head><body>Proxy down</body></html>";

    server
        .expect(requestTo(BASE_URL + "/orders"))
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body(html502));

    final KiteHttpClient client = new KiteHttpClient(props, delegate, builder);

    assertThatThrownBy(() -> client.get("/orders", OrderBookDto.class, Mutability.READ))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              final ApiException apiEx = (ApiException) ex;
              assertThat(apiEx.status()).isEqualTo(502);

              Throwable cur = apiEx;
              while (cur != null) {
                final String className = cur.getClass().getName();
                assertThat(className).doesNotContain("jackson");
                assertThat(className)
                    .satisfiesAnyOf(
                        c -> assertThat(c).startsWith("com.trading.bot.broker.api.error"),
                        c -> assertThat(c).startsWith("java.io"),
                        c -> assertThat(c).startsWith("java.net"),
                        c -> assertThat(c).startsWith("org.springframework.web.client"));
                cur = cur.getCause();
              }
            });

    server.verify();
  }

  @Test
  void timeoutConfigurationCarriesFiveTenFifteenSeconds() {
    final SessionDelegate delegate =
        new SessionDelegate() {
          @Override
          public String accessToken() {
            return TOKEN;
          }

          @Override
          public void forceRelogin() {}
        };

    final KiteHttpClient client = new KiteHttpClient(props, delegate, RestClient.builder());

    final Duration connectTimeout =
        (Duration) ReflectionTestUtils.getField(client, "connectTimeout");
    final Duration readTimeout = (Duration) ReflectionTestUtils.getField(client, "readTimeout");
    final Duration placeReadTimeout =
        (Duration) ReflectionTestUtils.getField(client, "placeReadTimeout");

    assertThat(connectTimeout).isEqualTo(Duration.ofSeconds(5));
    assertThat(readTimeout).isEqualTo(Duration.ofSeconds(10));
    assertThat(placeReadTimeout).isEqualTo(Duration.ofSeconds(15));
  }
}
