package com.trading.bot.broker.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.broker.api.error.AmbiguousOrderStateException;
import com.trading.bot.broker.api.error.ApiException;
import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.api.error.BrokerException;
import com.trading.bot.broker.api.error.NetworkException;
import com.trading.bot.broker.api.error.RateLimitException;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class ErrorHierarchyTest {

  @Test
  void allSubtypesAreBrokerExceptions() {
    final Throwable cause = new RuntimeException("root cause");

    final BrokerException auth = new AuthException("auth failed", cause);
    final BrokerException rate = new RateLimitException("rate limited", cause);
    final BrokerException net = new NetworkException("net drop", cause);
    final BrokerException amb =
        new AmbiguousOrderStateException("place", "tag-123", "timeout after sent", cause);
    final BrokerException api = new ApiException("OrderException", 400, "Invalid order", cause);

    assertThat(auth).isInstanceOf(BrokerException.class);
    assertThat(rate).isInstanceOf(BrokerException.class);
    assertThat(net).isInstanceOf(BrokerException.class);
    assertThat(amb).isInstanceOf(BrokerException.class);
    assertThat(api).isInstanceOf(BrokerException.class);
  }

  @Test
  void ambiguousOrderStateExceptionExposesGetters() {
    final Throwable cause = new RuntimeException("socket closed");
    final AmbiguousOrderStateException ex =
        new AmbiguousOrderStateException("placeOrder", "corr-456", "connection reset", cause);

    assertThat(ex.operation()).isEqualTo("placeOrder");
    assertThat(ex.correlationId()).isEqualTo("corr-456");
    assertThat(ex.detail()).isEqualTo("connection reset");
    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex.getMessage()).contains("placeOrder").contains("corr-456");
  }

  @Test
  void apiExceptionExposesGetters() {
    final Throwable cause = new RuntimeException("bad json");
    final ApiException ex = new ApiException("TokenException", 403, "Token expired", cause);

    assertThat(ex.errorType()).isEqualTo("TokenException");
    assertThat(ex.status()).isEqualTo(403);
    assertThat(ex.kiteMessage()).isEqualTo("Token expired");
    assertThat(ex.getCause()).isSameAs(cause);
  }
}
