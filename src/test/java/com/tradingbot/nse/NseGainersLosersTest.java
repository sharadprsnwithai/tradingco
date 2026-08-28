package com.tradingbot.nse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NseGainersLosersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testNseGainerLoserDeserializationWithAliases() throws Exception {
        String json = """
            {
                "symbol": "ATHERENERG",
                "series": "EQ",
                "open_price": 1523.7,
                "high_price": 1579.9,
                "low_price": 1523.7,
                "ltp": 1561.4,
                "prev_price": 1495.3,
                "net_price": 4.42,
                "trade_quantity": 3115287,
                "turnover": 48439.9,
                "market_type": "N",
                "perChange": 4.42
            }
        """;

        NseGainerLoser gl = objectMapper.readValue(json, NseGainerLoser.class);
        assertEquals("ATHERENERG", gl.symbol());
        assertEquals("EQ", gl.series());
        assertEquals(1561.4, gl.ltp());
        assertEquals(4.42, gl.change());
        assertEquals(4.42, gl.pChange());
        assertEquals(1523.7, gl.open());
        assertEquals(1579.9, gl.high());
        assertEquals(1523.7, gl.low());
        assertEquals(1495.3, gl.previousClose());
        assertEquals(3115287L, gl.totalTradedVolume());
    }

    @Test
    void testNseResponseWithFOSec() throws Exception {
        String responseJson = """
            {
                "legends": [["FOSec", "F&O Securities"]],
                "FOSec": {
                    "data": [
                        {
                            "symbol": "RELIANCE",
                            "series": "EQ",
                            "ltp": 3000.0,
                            "perChange": 2.5,
                            "net_price": 75.0,
                            "prev_price": 2925.0
                        }
                    ]
                }
            }
        """;

        var root = objectMapper.readTree(responseJson);
        assertTrue(root.has("FOSec"));
        var dataNode = root.path("FOSec").path("data");
        assertTrue(dataNode.isArray());

        NseGainerLoser gl = objectMapper.treeToValue(dataNode.get(0), NseGainerLoser.class);
        assertEquals("RELIANCE", gl.symbol());
        assertEquals(3000.0, gl.ltp());
        assertEquals(2.5, gl.pChange());
        assertEquals(2925.0, gl.previousClose());
    }
}
