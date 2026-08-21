package com.tradingbot.adapter;

import com.tradingbot.model.MarginInfo;
import com.tradingbot.model.Order;
import com.tradingbot.model.OrderModifyRequest;
import com.tradingbot.model.OrderRequest;
import com.tradingbot.model.OrderResult;
import com.tradingbot.model.Position;
import com.tradingbot.model.Tick;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface BrokerAdapter {

    /**
     * Unique identifier for the broker (e.g., "ZERODHA", "SHOONYA").
     */
    String getBrokerId();

    /**
     * Account ID associated with this adapter instance.
     */
    String getAccountId();

    /**
     * Authenticate and initialize the broker session.
     */
    Mono<Void> authenticate();

    /**
     * Check if the current session is active and valid.
     */
    Mono<Boolean> isSessionValid();

    /**
     * Whether this adapter is enabled via configuration.
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Place an order on the exchange through the broker.
     */
    Mono<OrderResult> placeOrder(OrderRequest request);

    /**
     * Modify an open order.
     */
    Mono<OrderResult> modifyOrder(String orderId, OrderModifyRequest request);

    /**
     * Cancel an open order.
     */
    Mono<Void> cancelOrder(String orderId);

    /**
     * Fetch the live order book from the broker.
     */
    Mono<List<Order>> getOrderBook();

    /**
     * Fetch open and closed positions from the broker.
     */
    Mono<List<Position>> getPositions();

    /**
     * Fetch available margins and account balances.
     */
    Mono<MarginInfo> getMargins();

    /**
     * Subscribe to real-time market data ticks for given symbols/tokens.
     */
    Flux<Tick> subscribeMarketData(List<String> symbols);
}
