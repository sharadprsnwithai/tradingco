package com.tradingbot.instrument;

import com.tradingbot.model.Instrument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentMasterServiceTest {

    @TempDir
    Path tempDir;

    private InstrumentMasterService service;

    @BeforeEach
    void setUp() {
        File dbFile = tempDir.resolve("test_instruments.db").toFile();
        service = new InstrumentMasterService(dbFile.getAbsolutePath());
        service.initSchema();
    }

    @AfterEach
    void tearDown() {
        service.clearActiveCache();
    }

    @Test
    void testSaveAndLookupInstruments() {
        Instrument nseReliance = Instrument.builder()
            .canonicalSymbol("NSE:RELIANCE")
            .kiteToken("738561")
            .shoonyaToken("2885")
            .exchange("NSE")
            .tradingSymbol("RELIANCE")
            .name("RELIANCE INDUSTRIES")
            .lotSize(1)
            .tickSize(new BigDecimal("0.05"))
            .instrumentType("EQ")
            .build();

        Instrument nfoNiftyCe = Instrument.builder()
            .canonicalSymbol("NFO:NIFTY24DEC24500CE")
            .kiteToken("123456")
            .shoonyaToken("45678")
            .exchange("NFO")
            .tradingSymbol("NIFTY24DEC24500CE")
            .name("NIFTY")
            .lotSize(25)
            .tickSize(new BigDecimal("0.05"))
            .instrumentType("CE")
            .strike(new BigDecimal("24500"))
            .expiry("2024-12-26")
            .build();

        StepVerifier.create(service.saveInstruments(List.of(nseReliance, nfoNiftyCe)))
            .verifyComplete();

        // Lookup by Canonical Symbol
        StepVerifier.create(service.findByCanonicalSymbol("NSE:RELIANCE"))
            .assertNext(inst -> {
                assertEquals("NSE:RELIANCE", inst.canonicalSymbol());
                assertEquals("738561", inst.kiteToken());
                assertEquals("2885", inst.shoonyaToken());
                assertEquals("EQ", inst.instrumentType());
            })
            .verifyComplete();

        // Verify active cache hit
        assertEquals(1, service.getActiveCacheSize());

        // Lookup by Kite Token
        StepVerifier.create(service.findByKiteToken("123456"))
            .assertNext(inst -> {
                assertEquals("NFO:NIFTY24DEC24500CE", inst.canonicalSymbol());
                assertEquals(25, inst.lotSize());
                assertEquals(new BigDecimal("24500.0"), inst.strike());
            })
            .verifyComplete();

        // Lookup by Shoonya Token
        StepVerifier.create(service.findByShoonyaToken("NSE", "2885"))
            .assertNext(inst -> assertEquals("NSE:RELIANCE", inst.canonicalSymbol()))
            .verifyComplete();

        // Lookup Option Contracts
        StepVerifier.create(service.findOptionContracts("NIFTY", "2024-12-26", new BigDecimal("24500"), "CE"))
            .assertNext(inst -> assertEquals("NFO:NIFTY24DEC24500CE", inst.canonicalSymbol()))
            .verifyComplete();
    }
}
