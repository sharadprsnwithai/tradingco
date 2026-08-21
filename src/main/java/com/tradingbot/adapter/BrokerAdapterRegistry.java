package com.tradingbot.adapter;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BrokerAdapterRegistry {

    private final Map<String, BrokerAdapter> adaptersByBrokerId = new ConcurrentHashMap<>();
    private final Map<String, BrokerAdapter> adaptersByAccountId = new ConcurrentHashMap<>();

    /**
     * Constructs a new registry and eagerly registers all supplied broker adapters.
     *
     * @param adapters the list of {@link BrokerAdapter} instances discovered via Spring dependency injection;
     *                 may be {@code null} or empty, in which case no adapters are registered
     */
    public BrokerAdapterRegistry(List<BrokerAdapter> adapters) {
        if (adapters != null) {
            for (BrokerAdapter adapter : adapters) {
                register(adapter);
            }
        }
    }

    /**
     * Registers a broker adapter so it can be looked up by its broker identifier and account identifier.
     * If an adapter with the same broker ID or account ID already exists, it will be replaced.
     *
     * @param adapter the {@link BrokerAdapter} to register; must not be {@code null}
     * @throws NullPointerException if {@code adapter.getBrokerId()} or {@code adapter.getAccountId()} is {@code null}
     */
    public void register(BrokerAdapter adapter) {
        adaptersByBrokerId.put(adapter.getBrokerId().toUpperCase(), adapter);
        adaptersByAccountId.put(adapter.getAccountId(), adapter);
    }

    /**
     * Retrieves the broker adapter whose broker identifier matches the given value (case-insensitive).
     *
     * @param brokerId the broker identifier to look up; may be {@code null}
     * @return a {@link Mono} emitting the matching {@link BrokerAdapter}, or an empty {@link Mono}
     *         if no adapter is registered for the given broker ID
     */
    public Mono<BrokerAdapter> getByBrokerId(String brokerId) {
        if (brokerId == null) {
            return Mono.empty();
        }
        BrokerAdapter adapter = adaptersByBrokerId.get(brokerId.toUpperCase());
        return adapter != null ? Mono.just(adapter) : Mono.empty();
    }

    /**
     * Synchronously retrieves the broker adapter for the given broker identifier.
     * Safe to call from any thread (backed by a ConcurrentHashMap) — prefer this over
     * {@link #getByBrokerId(String)} in code paths that cannot block a reactor thread.
     *
     * @param brokerId the broker identifier to look up; may be {@code null}
     * @return an Optional containing the adapter, or empty if not registered
     */
    public java.util.Optional<BrokerAdapter> findByBrokerId(String brokerId) {
        if (brokerId == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(adaptersByBrokerId.get(brokerId.toUpperCase()));
    }

    /**
     * Retrieves the broker adapter whose account identifier matches the given value.
     *
     * @param accountId the account identifier to look up; may be {@code null}
     * @return a {@link Mono} emitting the matching {@link BrokerAdapter}, or an empty {@link Mono}
     *         if no adapter is registered for the given account ID
     */
    public Mono<BrokerAdapter> getByAccountId(String accountId) {
        if (accountId == null) {
            return Mono.empty();
        }
        BrokerAdapter adapter = adaptersByAccountId.get(accountId);
        return adapter != null ? Mono.just(adapter) : Mono.empty();
    }

    /**
     * Returns a stream of all currently registered broker adapters.
     *
     * @return a {@link Flux} emitting every {@link BrokerAdapter} in the registry
     */
    public Flux<BrokerAdapter> getAll() {
        return Flux.fromIterable(adaptersByBrokerId.values());
    }
}
