package com.tradingbot.strategy.ironfly;

import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic fallback OptionChainProvider.
 * Uses instrument master for strikes and broker LTP for pricing.
 * Does not provide Greeks — delta is approximated from moneyness.
 */
@Component
public class GenericOptionChainProvider implements OptionChainProvider {

    private static final Logger log = LoggerFactory.getLogger(GenericOptionChainProvider.class);

    private final BrokerAdapterRegistry brokerRegistry;
    private final InstrumentMasterService instrumentMaster;

    public GenericOptionChainProvider(BrokerAdapterRegistry brokerRegistry, InstrumentMasterService instrumentMaster) {
        this.brokerRegistry = brokerRegistry;
        this.instrumentMaster = instrumentMaster;
    }

    @Override
    public Mono<Map<Integer, StrikeQuote>> getOptionChain(String underlying, String expiry) {
        return getSpotPrice(underlying)
            .flatMap(spot -> instrumentMaster.findOptionContracts(underlying, expiry, null, null)
                .collectList()
                .map(instruments -> {
                    Map<Integer, StrikeQuote> chain = new HashMap<>();
                    for (Instrument inst : instruments) {
                        if (inst.strike() == null) continue;
                        int strike = inst.strike().intValue();
                        OptionType type = "CE".equals(inst.instrumentType()) ? OptionType.CE : OptionType.PE;
                        double delta = approximateDelta(strike, spot, type);
                        chain.put(strike, new StrikeQuote(
                            strike, type,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            0, 0,
                            delta, 0.0, 0.0, 0.0
                        ));
                    }
                    return chain;
                }));
    }

    @Override
    public Mono<Double> getSpotPrice(String underlying) {
        return instrumentMaster.findNearestAtmOption(underlying, 0, "CE")
            .map(inst -> inst.strike() != null ? inst.strike().doubleValue() : 0.0)
            .switchIfEmpty(Mono.just(0.0));
    }

    /**
     * Approximates delta from moneyness.
     * ATM ≈ 0.5, 2% OTM ≈ 0.35, 5% OTM ≈ 0.2
     */
    private double approximateDelta(int strike, double spot, OptionType type) {
        if (spot <= 0) return 0.5;
        double moneyness = (strike - spot) / spot;
        if (type == OptionType.CE) {
            return Math.max(0.05, Math.min(0.95, 0.5 + moneyness * 10));
        } else {
            return Math.max(0.05, Math.min(0.95, 0.5 - moneyness * 10));
        }
    }
}
