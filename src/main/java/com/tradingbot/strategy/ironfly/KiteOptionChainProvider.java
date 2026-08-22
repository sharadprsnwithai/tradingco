package com.tradingbot.strategy.ironfly;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
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

    public KiteOptionChainProvider(BrokerAdapterRegistry brokerRegistry, InstrumentMasterService instrumentMaster) {
        this.brokerRegistry = brokerRegistry;
        this.instrumentMaster = instrumentMaster;
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

                List<String> tokens = instruments.stream()
                    .map(Instrument::kiteToken)
                    .filter(t -> t != null && !t.isBlank())
                    .toList();

                if (tokens.isEmpty()) return Mono.just(Map.<Integer, StrikeQuote>of());

                return adapter.subscribeMarketData(tokens)
                    .take(1)
                    .collectList()
                    .map(ticks -> {
                        Map<Integer, StrikeQuote> chain = new HashMap<>();
                        for (Instrument inst : instruments) {
                            if (inst.kiteToken() == null) continue;
                            ticks.stream()
                                .filter(t -> inst.kiteToken().equals(t.instrumentToken()))
                                .findFirst()
                                .ifPresent(tick -> {
                                    OptionType type = "CE".equals(inst.instrumentType()) ? OptionType.CE : OptionType.PE;
                                    int strike = inst.strike() != null ? inst.strike().intValue() : 0;
                                    StrikeQuote quote = new StrikeQuote(
                                        strike, type,
                                        tick.ltp() != null ? tick.ltp() : BigDecimal.ZERO,
                                        tick.ltp() != null ? tick.ltp() : BigDecimal.ZERO,
                                        tick.ltp() != null ? tick.ltp() : BigDecimal.ZERO,
                                        0, 0,
                                        0.0, 0.0, 0.0, 0.0
                                    );
                                    chain.merge(strike, quote, (existing, newQ) -> {
                                        if (existing.optionType() == OptionType.CE && newQ.optionType() == OptionType.PE) {
                                            return new StrikeQuote(strike, existing.optionType(),
                                                existing.ltp(), existing.bid(), existing.ask(),
                                                existing.openInterest(), existing.volume(),
                                                existing.delta(), existing.gamma(), existing.theta(), existing.vega());
                                        }
                                        return existing;
                                    });
                                    if (type == OptionType.PE && chain.containsKey(strike)) {
                                        StrikeQuote ce = chain.get(strike);
                                        chain.put(strike, new StrikeQuote(strike, type,
                                            ce.ltp(), ce.bid(), ce.ask(),
                                            ce.openInterest(), ce.volume(),
                                            ce.delta(), ce.gamma(), ce.theta(), ce.vega()));
                                    }
                                });
                        }
                        return chain;
                    });
            });
    }

    @Override
    public Mono<Double> getSpotPrice(String underlying) {
        return brokerRegistry.getAll()
            .filter(BrokerAdapter::isEnabled)
            .take(1)
            .singleOrEmpty()
            .flatMap(adapter -> adapter.getPositions()
                .flatMapMany(Flux::fromIterable)
                .filter(p -> p.symbol() != null && p.symbol().contains(underlying))
                .take(1)
                .map(p -> p.ltp() != null ? p.ltp().doubleValue() : 0.0)
                .singleOrEmpty()
                .switchIfEmpty(Mono.just(0.0)));
    }
}
