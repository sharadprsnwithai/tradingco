package com.tradingbot.strategy.ironfly;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tradingbot.marketdata.KitePcrProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kite (Zerodha) implementation of OptionChainProvider.
 * Uses the instrument master to find option contracts and broker quotes for pricing.
 */
@Primary
@Component
public class KiteOptionChainProvider implements OptionChainProvider {

    private static final Logger log = LoggerFactory.getLogger(KiteOptionChainProvider.class);

    private final BrokerAdapterRegistry brokerRegistry;
    private final InstrumentMasterService instrumentMaster;
    private final KitePcrProvider kitePcrProvider;

    public KiteOptionChainProvider(
        BrokerAdapterRegistry brokerRegistry,
        InstrumentMasterService instrumentMaster,
        @Autowired(required = false) KitePcrProvider kitePcrProvider
    ) {
        this.brokerRegistry = brokerRegistry;
        this.instrumentMaster = instrumentMaster;
        this.kitePcrProvider = kitePcrProvider;
    }

    @Override
    public Mono<Map<Integer, StrikeQuote>> getOptionChain(String underlying, String expiry) {
        return brokerRegistry.getAll()
            .filter(BrokerAdapter::isEnabled)
            .take(1)
            .singleOrEmpty()
            .flatMap(adapter -> fetchChainFromBroker(adapter, underlying, expiry))
            .doOnError(e -> log.warn("Failed to fetch option chain from Kite for {}: {}", underlying, e.getMessage()));
    }

    private Mono<Map<Integer, StrikeQuote>> fetchChainFromBroker(BrokerAdapter adapter, String underlying, String expiry) {
        return instrumentMaster.findOptionContracts(underlying, expiry, null, "CE")
            .concatWith(instrumentMaster.findOptionContracts(underlying, expiry, null, "PE"))
            .collectList()
            .flatMap(instruments -> {
                if (instruments.isEmpty()) return Mono.just(Map.<Integer, StrikeQuote>of());

                List<String> canonicalSymbols = instruments.stream()
                    .map(Instrument::canonicalSymbol)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();

                if (canonicalSymbols.isEmpty()) return Mono.just(Map.<Integer, StrikeQuote>of());

                return adapter.subscribeMarketData(canonicalSymbols)
                    .take(Duration.ofSeconds(2))
                    .collectList()
                    .map(ticks -> {
                        Map<Integer, StrikeQuote> chain = new HashMap<>();
                        for (Instrument inst : instruments) {
                            OptionType type = "CE".equals(inst.instrumentType()) ? OptionType.CE : OptionType.PE;
                            int strike = inst.strike() != null ? inst.strike().intValue() : 0;
                            
                            // Check for tick from subscription, else fetch LTP directly
                            double ltp = 0.0;
                            var matchedTick = ticks.stream()
                                .filter(t -> inst.canonicalSymbol().equals(t.symbol()))
                                .findFirst();
                            if (matchedTick.isPresent() && matchedTick.get().ltp() != null) {
                                ltp = matchedTick.get().ltp().doubleValue();
                            } else if (kitePcrProvider != null) {
                                ltp = kitePcrProvider.fetchLtp(inst.canonicalSymbol());
                            }

                            if (ltp > 0) {
                                StrikeQuote quote = new StrikeQuote(
                                    strike, type,
                                    BigDecimal.valueOf(ltp),
                                    BigDecimal.valueOf(ltp),
                                    BigDecimal.valueOf(ltp),
                                    10000, 500,
                                    0.0, 0.0, 0.0, 0.0
                                );
                                chain.put(strike, quote);
                            }
                        }
                        return chain;
                    });
            });
    }

    @Override
    public Mono<Double> getSpotPrice(String underlying) {
        String sym = underlying.equalsIgnoreCase("NIFTY") ? "NSE:NIFTY 50" : (underlying.startsWith("NSE:") ? underlying : "NSE:" + underlying);
        return Mono.fromCallable(() -> {
            if (kitePcrProvider != null) {
                double ltp = kitePcrProvider.fetchLtp(sym);
                if (ltp > 0) return ltp;
            }
            return 0.0;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
          .switchIfEmpty(Mono.just(0.0));
    }
}
