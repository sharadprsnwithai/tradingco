package com.trading.bot.broker.api;

import com.trading.bot.broker.api.error.AmbiguousOrderStateException;
import com.trading.bot.broker.api.error.ApiException;
import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.api.error.NetworkException;
import com.trading.bot.broker.api.error.RateLimitException;
import com.trading.bot.broker.api.model.Holding;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Margins;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderPatch;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.Position;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Broker port interface defining order operations and account telemetry. */
public interface Broker {

  /**
   * Places a new order with Kite.
   *
   * @param request order details
   * @return placed order ID
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws AmbiguousOrderStateException if mutation state is indeterminate
   * @throws ApiException on API error responses
   */
  OrderId placeOrder(OrderRequest request);

  /**
   * Modifies an existing open order.
   *
   * @param id order ID to modify
   * @param patch modified values
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws AmbiguousOrderStateException if mutation state is indeterminate
   * @throws ApiException on API error responses
   */
  void modifyOrder(OrderId id, OrderPatch patch);

  /**
   * Cancels an existing open order.
   *
   * @param id order ID to cancel
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws AmbiguousOrderStateException if mutation state is indeterminate
   * @throws ApiException on API error responses
   */
  void cancelOrder(OrderId id);

  /**
   * Retrieves the current order book.
   *
   * @return list of orders
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws ApiException on API error responses
   */
  List<Order> orderBook();

  /**
   * Retrieves current portfolio positions.
   *
   * @return list of positions
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws ApiException on API error responses
   */
  List<Position> positions();

  /**
   * Retrieves current portfolio holdings.
   *
   * @return list of holdings
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws ApiException on API error responses
   */
  List<Holding> holdings();

  /**
   * Retrieves account fund and segment margins.
   *
   * @return margins detail
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws ApiException on API error responses
   */
  Margins margins();

  /**
   * Retrieves live quotes for specified quote references.
   *
   * @param refs quote references specifying token, exchange, and tradingsymbol
   * @return map of instrument token to quote
   * @throws AuthException if unauthenticated or session expired
   * @throws RateLimitException if rate limit exceeded (HTTP 429)
   * @throws NetworkException on network communication failure
   * @throws ApiException on API error responses
   */
  Map<InstrumentToken, Quote> quotes(Collection<QuoteRef> refs);
}
