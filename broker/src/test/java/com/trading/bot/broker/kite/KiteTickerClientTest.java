package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.config.KiteProperties;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class KiteTickerClientTest {

  @Test
  void parsesBinaryLtpPacketCorrectly() {
    final KiteProperties props =
        new KiteProperties("key", "user", "pass", "totp", null, null, null, null);
    final SessionDelegate session = mock(SessionDelegate.class);
    when(session.accessToken()).thenReturn("active_token");

    final KiteTickerClient client = new KiteTickerClient(props, session);

    // Construct a synthetic 8-byte LTP packet:
    // Header: 2 bytes (numPackets = 1)
    // Packet: 2 bytes (length = 8)
    // Token: 4 bytes (256265 = NIFTY 50)
    // Price: 4 bytes (2500050 = 25000.50)
    final ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
    buf.putShort((short) 1); // 1 packet
    buf.putShort((short) 8); // packet length 8 bytes
    buf.putInt(256265); // token
    buf.putInt(2500050); // 25,000.50 * 100
    buf.flip();

    client.processBinaryMessage(buf);

    final Optional<BigDecimal> ltp = client.getLtp(256265L);
    assertThat(ltp).isPresent();
    assertThat(ltp.get()).isEqualByComparingTo(BigDecimal.valueOf(25000.50));
  }

  @Test
  void parsesMultiplePacketsInSingleFrame() {
    final KiteProperties props =
        new KiteProperties("key", "user", "pass", "totp", null, null, null, null);
    final SessionDelegate session = mock(SessionDelegate.class);
    when(session.accessToken()).thenReturn("active_token");

    final KiteTickerClient client = new KiteTickerClient(props, session);

    // Frame with 2 packets
    final ByteBuffer buf = ByteBuffer.allocate(2 + (2 + 8) * 2).order(ByteOrder.BIG_ENDIAN);
    buf.putShort((short) 2);

    // Packet 1
    buf.putShort((short) 8);
    buf.putInt(101);
    buf.putInt(15025); // 150.25

    // Packet 2
    buf.putShort((short) 8);
    buf.putInt(102);
    buf.putInt(7550); // 75.50
    buf.flip();

    client.processBinaryMessage(buf);

    assertThat(client.getLtp(101L)).contains(BigDecimal.valueOf(150.25));
    assertThat(client.getLtp(102L)).contains(BigDecimal.valueOf(75.50));
  }

  @Test
  void generatesSubscriptionJson() {
    final KiteProperties props =
        new KiteProperties("key", "user", "pass", "totp", null, null, null, null);
    final SessionDelegate session = mock(SessionDelegate.class);

    final KiteTickerClient client = new KiteTickerClient(props, session);
    final String json = client.buildSubscriptionMessage(Set.of(256265L, 12345L));

    assertThat(json).contains("\"a\":\"subscribe\"");
    assertThat(json).contains("256265");
    assertThat(json).contains("12345");
  }
}
