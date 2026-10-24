package com.trading.bot.broker.kite;

import com.trading.bot.broker.config.KiteProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Real-time streaming WebSocket client for Zerodha KiteTicker. Consumes binary tick feeds with zero
 * REST API rate limit consumption.
 */
@Component
public final class KiteTickerClient implements WebSocket.Listener {

  private static final Logger LOG = LoggerFactory.getLogger(KiteTickerClient.class);
  private static final String DEFAULT_WS_URL = "wss://ws.kite.trade";
  private static final int PACKET_HEADER_SIZE = 2;
  private static final int LTP_PACKET_LENGTH = 8;
  private static final int QUOTE_PACKET_MIN_LENGTH = 28;
  private static final double PRICE_DIVISOR = 100.0;

  private final KiteProperties props;
  private final SessionDelegate session;
  private final Map<Long, BigDecimal> ltpCache = new ConcurrentHashMap<>();
  private final Set<Long> subscribedTokens = ConcurrentHashMap.newKeySet();

  private WebSocket webSocket;
  private volatile boolean connected;

  @Autowired
  public KiteTickerClient(KiteProperties props, SessionDelegate session) {
    this.props = Objects.requireNonNull(props, "props must not be null");
    this.session = Objects.requireNonNull(session, "session must not be null");
  }

  /** Connects to KiteTicker WebSocket endpoint using current access token on startup or reauth. */
  @EventListener(ApplicationReadyEvent.class)
  @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
  public synchronized void connect() {
    try {
      final String token = session.accessToken();
      if (token == null || token.isBlank()) {
        return;
      }

      final String wsUrl = DEFAULT_WS_URL + "?api_key=" + props.apiKey() + "&access_token=" + token;

      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

      final CompletableFuture<WebSocket> wsFuture =
          client.newWebSocketBuilder().buildAsync(URI.create(wsUrl), this);

      this.webSocket = wsFuture.get();
      this.connected = true;

      if (!subscribedTokens.isEmpty()) {
        subscribe(subscribedTokens);
      }
      if (LOG.isInfoEnabled()) {
        LOG.info("KiteTicker WebSocket connected successfully");
      }
    } catch (Exception e) {
      this.connected = false;
      if (LOG.isWarnEnabled()) {
        LOG.warn("KiteTicker WebSocket connection notice: {}", e.getMessage());
      }
    }
  }

  /**
   * Subscribes to live streaming quotes for the given instrument tokens.
   *
   * @param tokens collection of numeric tokens
   */
  public synchronized void subscribe(Collection<Long> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      return;
    }
    subscribedTokens.addAll(tokens);

    if (webSocket != null && connected) {
      final String subMsg = buildSubscriptionMessage(tokens);
      final String modeMsg = buildModeMessage("ltp", tokens);
      webSocket.sendText(subMsg, true).thenCompose(ws -> ws.sendText(modeMsg, true));
    }
  }

  /**
   * Retrieves the latest cached streaming LTP for a token if available.
   *
   * @param token numeric instrument token
   * @return optional last traded price
   */
  public Optional<BigDecimal> getLtp(long token) {
    return Optional.ofNullable(ltpCache.get(token));
  }

  /**
   * Returns true if WebSocket feed is currently connected.
   *
   * @return true if connected
   */
  public boolean isConnected() {
    return connected;
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    this.connected = true;
    webSocket.request(1);
  }

  @Override
  public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
    processBinaryMessage(data);
    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    this.connected = false;
    if (LOG.isInfoEnabled()) {
      LOG.info("KiteTicker WebSocket closed: code={} reason={}", statusCode, reason);
    }
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    this.connected = false;
    if (LOG.isWarnEnabled()) {
      LOG.warn("KiteTicker WebSocket error: {}", error.getMessage());
    }
  }

  /**
   * Parses Zerodha binary tick packet frames.
   *
   * @param data incoming binary buffer
   */
  public void processBinaryMessage(ByteBuffer data) {
    if (data == null || data.remaining() < PACKET_HEADER_SIZE) {
      return;
    }

    data.order(ByteOrder.BIG_ENDIAN);
    final short numPackets = data.getShort();

    for (int i = 0; i < numPackets && data.remaining() >= PACKET_HEADER_SIZE; i++) {
      final short packetLength = data.getShort();
      if (packetLength <= 0 || data.remaining() < packetLength) {
        break;
      }

      if (packetLength == LTP_PACKET_LENGTH) {
        final int token = data.getInt();
        final int ltpInt = data.getInt();
        final BigDecimal ltp = BigDecimal.valueOf(ltpInt / PRICE_DIVISOR);
        ltpCache.put((long) token, ltp);
      } else if (packetLength >= QUOTE_PACKET_MIN_LENGTH) {
        final int token = data.getInt();
        final int ltpInt = data.getInt();
        final BigDecimal ltp = BigDecimal.valueOf(ltpInt / PRICE_DIVISOR);
        ltpCache.put((long) token, ltp);
        data.position(data.position() + packetLength - 8);
      } else {
        data.position(data.position() + packetLength);
      }
    }
  }

  String buildSubscriptionMessage(Collection<Long> tokens) {
    final String listStr = tokens.stream().map(String::valueOf).collect(Collectors.joining(","));
    return "{\"a\":\"subscribe\",\"v\":[" + listStr + "]}";
  }

  String buildModeMessage(String mode, Collection<Long> tokens) {
    final String listStr = tokens.stream().map(String::valueOf).collect(Collectors.joining(","));
    return "{\"a\":\"mode\",\"v\":[\"" + mode + "\",[" + listStr + "]]}";
  }

  @PreDestroy
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public synchronized void close() {
    if (webSocket != null) {
      try {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Application shutdown");
      } catch (Exception ignored) {
        // ignore
      }
      this.connected = false;
    }
  }
}
