package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.AmbiguousOrderStateException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.Holding;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Margins;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderPatch;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Position;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
import com.trading.bot.broker.kite.dto.HoldingDto;
import com.trading.bot.broker.kite.dto.MarginsDto;
import com.trading.bot.broker.kite.dto.OrderBookDto;
import com.trading.bot.broker.kite.dto.OrderDto;
import com.trading.bot.broker.kite.dto.PlaceOrderResponseDto;
import com.trading.bot.broker.kite.dto.PositionDto;
import com.trading.bot.broker.kite.dto.QuoteDataDto;
import com.trading.bot.broker.kite.dto.QuoteDto;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Broker port adapter implementing order operations and telemetry against Kite Connect API. */
@Component
public final class KiteBroker implements Broker {

  private static final int MAX_TAG_LENGTH = 20;
  private static final DateTimeFormatter KITE_TS_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final KiteHttpClient httpClient;
  private final ExecutionTracker tracker;
  private final NotificationPublisher publisher;
  private final KiteTickerClient tickerClient;
  private final Clock clock;
  private final AtomicLong tagCounter = new AtomicLong(0);
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public KiteBroker(
      KiteHttpClient httpClient,
      ExecutionTracker tracker,
      NotificationPublisher publisher,
      @Autowired(required = false) KiteTickerClient tickerClient) {
    this(httpClient, tracker, publisher, tickerClient, Clock.system(ZoneId.of("Asia/Kolkata")));
  }

  public KiteBroker(
      KiteHttpClient httpClient,
      ExecutionTracker tracker,
      NotificationPublisher publisher,
      Clock clock) {
    this(httpClient, tracker, publisher, null, clock);
  }

  public KiteBroker(
      KiteHttpClient httpClient,
      ExecutionTracker tracker,
      NotificationPublisher publisher,
      KiteTickerClient tickerClient,
      Clock clock) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.tickerClient = tickerClient;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Generates a unique lowercase alphanumeric tag ≤ 20 characters per spec §7.
   *
   * @return auto-generated tag
   */
  public String generateAutoTag() {
    final long epochMillis = clock.millis();
    final long count = tagCounter.incrementAndGet();
    final int rnd = random.nextInt(0xFFFFFF);

    final String timePart = Long.toString(epochMillis, 36);
    final String countPart = Long.toString(count, 36);
    final String rndPart = Integer.toString(rnd, 36);

    String tag = ("t" + timePart + countPart + rndPart).toLowerCase(Locale.ROOT);
    if (tag.length() > MAX_TAG_LENGTH) {
      tag = tag.substring(0, MAX_TAG_LENGTH);
    }
    return tag;
  }

  @Override
  public OrderId placeOrder(OrderRequest request) {
    Objects.requireNonNull(request, "request must not be null");

    final String tag = request.tag().filter(t -> !t.isBlank()).orElseGet(this::generateAutoTag);

    final Map<String, String> form = new LinkedHashMap<>();
    form.put("exchange", request.exchange().name());
    form.put("tradingsymbol", request.tradingsymbol());
    form.put("transaction_type", request.transactionType().name());
    form.put("order_type", request.orderType().toKite());
    form.put("quantity", String.valueOf(request.quantity()));
    form.put("product", request.product().name());
    form.put("validity", request.validity().name());
    form.put("tag", tag);

    request.price().ifPresent(p -> form.put("price", p.toPlainString()));
    request.triggerPrice().ifPresent(tp -> form.put("trigger_price", tp.toPlainString()));

    try {
      final PlaceOrderResponseDto response =
          httpClient.post(
              "/orders/regular", form, PlaceOrderResponseDto.class, Mutability.MUTATION);

      final OrderId orderId = new OrderId(response.data().orderId());
      tracker.register(orderId, tag, request);

      final String details =
          request.transactionType()
              + " "
              + request.tradingsymbol()
              + " qty="
              + request.quantity()
              + " tag="
              + tag
              + " orderId="
              + orderId.value();

      publisher.publish(
          Notification.of(
              Severity.INFO,
              NotificationType.ORDER_SUBMITTED,
              "Order submitted: " + orderId.value(),
              details));

      return orderId;
    } catch (AmbiguousOrderStateException e) {
      publisher.publish(
          new Notification(
              Severity.CRITICAL,
              NotificationType.ORDER_AMBIGUOUS,
              "Order placement ambiguous: " + tag,
              e.getMessage(),
              e,
              clock.instant()));
      throw e;
    }
  }

  @Override
  public void modifyOrder(OrderId id, OrderPatch patch) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(patch, "patch must not be null");

    final Map<String, String> form = new LinkedHashMap<>();
    patch.quantity().ifPresent(q -> form.put("quantity", String.valueOf(q)));
    patch.price().ifPresent(p -> form.put("price", p.toPlainString()));
    patch.triggerPrice().ifPresent(tp -> form.put("trigger_price", tp.toPlainString()));

    httpClient.post("/orders/regular/" + id.value(), form, Object.class, Mutability.MUTATION);
  }

  @Override
  public void cancelOrder(OrderId id) {
    Objects.requireNonNull(id, "id must not be null");
    httpClient.post(
        "/orders/regular/" + id.value(), Collections.emptyMap(), Object.class, Mutability.MUTATION);
  }

  @Override
  public List<Order> orderBook() {
    final OrderBookDto dto = httpClient.get("/orders", OrderBookDto.class, Mutability.READ);
    if (dto == null || dto.data() == null) {
      return List.of();
    }

    return dto.data().stream().map(this::mapOrderDto).toList();
  }

  @Override
  public List<Position> positions() {
    final PositionDto dto =
        httpClient.get("/portfolio/positions", PositionDto.class, Mutability.READ);
    if (dto == null || dto.data() == null || dto.data().net() == null) {
      return List.of();
    }

    return dto.data().net().stream().map(this::mapPositionDto).toList();
  }

  @Override
  public List<Holding> holdings() {
    final HoldingDto dto = httpClient.get("/portfolio/holdings", HoldingDto.class, Mutability.READ);
    if (dto == null || dto.data() == null) {
      return List.of();
    }

    return dto.data().stream().map(this::mapHoldingDto).toList();
  }

  @Override
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public Margins margins() {
    final MarginsDto dto = httpClient.get("/user/margins", MarginsDto.class, Mutability.READ);
    final Map<String, BigDecimal> funds = new HashMap<>();
    final Map<String, Map<String, BigDecimal>> segments = new HashMap<>();

    if (dto != null && dto.data() != null) {
      for (final Map.Entry<String, Object> entry : dto.data().entrySet()) {
        final Map<String, BigDecimal> segMap = new HashMap<>();
        if (entry.getValue() instanceof Map<?, ?> inner) {
          for (final Map.Entry<?, ?> innerEntry : inner.entrySet()) {
            if (innerEntry.getValue() instanceof Number num) {
              segMap.put(
                  String.valueOf(innerEntry.getKey()), BigDecimal.valueOf(num.doubleValue()));
            }
          }
        }
        segments.put(entry.getKey(), segMap);
      }
    }

    return new Margins(funds, segments);
  }

  @Override
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public Map<InstrumentToken, Quote> quotes(Collection<QuoteRef> refs) {
    if (refs == null || refs.isEmpty()) {
      return Map.of();
    }

    final Map<InstrumentToken, Quote> result = new HashMap<>();
    final List<QuoteRef> missingRefs = new ArrayList<>();

    if (tickerClient != null) {
      final List<Long> tokensToSubscribe = new ArrayList<>();
      for (final QuoteRef ref : refs) {
        final Optional<BigDecimal> wsLtp = tickerClient.getLtp(ref.token().value());
        if (wsLtp.isPresent()) {
          result.put(
              ref.token(),
              new Quote(
                  ref.token(),
                  ref.tradingsymbol(),
                  wsLtp.get(),
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  0L));
        } else {
          missingRefs.add(ref);
          tokensToSubscribe.add(ref.token().value());
        }
      }
      if (!tokensToSubscribe.isEmpty()) {
        tickerClient.subscribe(tokensToSubscribe);
      }
    } else {
      missingRefs.addAll(refs);
    }

    if (missingRefs.isEmpty()) {
      return result;
    }

    final String query =
        missingRefs.stream()
            .map(r -> "i=" + r.exchange().name() + ":" + r.tradingsymbol())
            .collect(Collectors.joining("&"));

    final QuoteDto dto = httpClient.get("/quote?" + query, QuoteDto.class, Mutability.READ);
    if (dto == null || dto.data() == null) {
      return result;
    }

    for (final QuoteRef ref : missingRefs) {
      final String key = ref.exchange().name() + ":" + ref.tradingsymbol();
      final QuoteDataDto qData = dto.data().get(key);
      if (qData != null) {
        final Quote quote =
            new Quote(
                ref.token(),
                ref.tradingsymbol(),
                qData.lastPrice(),
                qData.ohlc() == null ? BigDecimal.ZERO : qData.ohlc().open(),
                qData.ohlc() == null ? BigDecimal.ZERO : qData.ohlc().high(),
                qData.ohlc() == null ? BigDecimal.ZERO : qData.ohlc().low(),
                qData.ohlc() == null ? BigDecimal.ZERO : qData.ohlc().close(),
                qData.volume());
        result.put(ref.token(), quote);
      }
    }

    return result;
  }

  private Order mapOrderDto(OrderDto dto) {
    final OrderStatus status = OrderStatus.fromKite(dto.status());
    final Instant ts = parseTimestamp(dto.orderTimestamp());

    return new Order(
        new OrderId(dto.orderId()),
        Optional.ofNullable(dto.tag()).filter(t -> !t.isBlank()),
        Exchange.fromKite(dto.exchange()),
        dto.tradingsymbol(),
        TransactionType.fromKite(dto.transactionType()),
        OrderType.fromKite(dto.orderType()),
        Product.fromKite(dto.product()),
        Validity.fromKite(dto.validity()),
        status,
        dto.status() == null ? "" : dto.status(),
        dto.quantity(),
        dto.filledQuantity(),
        dto.pendingQuantity(),
        dto.price(),
        dto.triggerPrice(),
        dto.averagePrice(),
        dto.statusMessage() == null ? "" : dto.statusMessage(),
        ts);
  }

  private Position mapPositionDto(PositionDto.PositionItemDto item) {
    return new Position(
        Exchange.fromKite(item.exchange()),
        item.tradingsymbol(),
        Product.fromKite(item.product()),
        item.quantity(),
        item.overnightQuantity(),
        item.multiplier(),
        item.averagePrice(),
        item.pnl());
  }

  private Holding mapHoldingDto(HoldingDto.HoldingItemDto item) {
    return new Holding(
        Exchange.fromKite(item.exchange()),
        item.tradingsymbol(),
        item.quantity(),
        item.t1Quantity(),
        item.averagePrice(),
        item.lastPrice(),
        item.pnl());
  }

  private Instant parseTimestamp(String raw) {
    if (raw == null || raw.isBlank()) {
      return clock.instant();
    }
    try {
      final LocalDateTime ldt = LocalDateTime.parse(raw, KITE_TS_FORMAT);
      return ldt.atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    } catch (java.time.format.DateTimeParseException ignored) {
      return clock.instant();
    }
  }
}
