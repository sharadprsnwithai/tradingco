package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.error.AmbiguousOrderStateException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.Holding;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Margins;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidDuplicateLiterals"})
class KiteBrokerTest {

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

  private OrderRequest makeRequest(Optional<String> tag) {
    return new OrderRequest(
        new InstrumentToken(256265L),
        "RELIANCE",
        Exchange.NSE,
        TransactionType.BUY,
        OrderType.LIMIT,
        Product.MIS,
        Validity.DAY,
        10,
        Optional.of(BigDecimal.valueOf(2500)),
        Optional.empty(),
        tag);
  }

  @Test
  void happyPlaceOrderBuildsWireFormPublishesSubmittedAndReturnsOrderId() {
    final KiteHttpClient client = mock(KiteHttpClient.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;

    when(client.post(
            eq("/orders/regular"), any(), eq(PlaceOrderResponseDto.class), eq(Mutability.MUTATION)))
        .thenReturn(
            new PlaceOrderResponseDto(
                "success", new PlaceOrderResponseDto.PlaceOrderDataDto("2410230000001")));

    final KiteBroker broker = new KiteBroker(client, tracker, publisher, clock);

    final OrderId orderId = broker.placeOrder(makeRequest(Optional.of("my-custom-tag")));

    assertThat(orderId.value()).isEqualTo("2410230000001");

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, String>> formCaptor = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .post(
            eq("/orders/regular"),
            formCaptor.capture(),
            eq(PlaceOrderResponseDto.class),
            eq(Mutability.MUTATION));

    final Map<String, String> form = formCaptor.getValue();
    assertThat(form)
        .containsEntry("exchange", "NSE")
        .containsEntry("tradingsymbol", "RELIANCE")
        .containsEntry("transaction_type", "BUY")
        .containsEntry("order_type", "LIMIT")
        .containsEntry("quantity", "10")
        .containsEntry("price", "2500")
        .containsEntry("product", "MIS")
        .containsEntry("validity", "DAY")
        .containsEntry("tag", "my-custom-tag");

    verify(tracker).register(eq(orderId), eq("my-custom-tag"), any(OrderRequest.class));

    assertThat(published).hasSize(1);
    final Notification submitted = published.get(0);
    assertThat(submitted.type()).isEqualTo(NotificationType.ORDER_SUBMITTED);
    assertThat(submitted.body()).contains("my-custom-tag").contains("2410230000001");
  }

  @Test
  void hundredPlacesInSameSecondGenerateDistinctAlphanumericTagsUnderTwentyChars() {
    final KiteHttpClient client = mock(KiteHttpClient.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final NotificationPublisher publisher = n -> {};
    final KiteBroker broker = new KiteBroker(client, tracker, publisher, clock);

    when(client.post(
            eq("/orders/regular"), any(), eq(PlaceOrderResponseDto.class), eq(Mutability.MUTATION)))
        .thenReturn(
            new PlaceOrderResponseDto(
                "success", new PlaceOrderResponseDto.PlaceOrderDataDto("id-123")));

    final Set<String> generatedTags = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      final String tag = broker.generateAutoTag();
      assertThat(tag).matches("^[a-z0-9]+$");
      assertThat(tag.length()).isLessThanOrEqualTo(20);
      generatedTags.add(tag);
    }

    assertThat(generatedTags).hasSize(100);
  }

  @Test
  void callerSuppliedTagIsUsedVerbatim() {
    final KiteHttpClient client = mock(KiteHttpClient.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final NotificationPublisher publisher = n -> {};
    final KiteBroker broker = new KiteBroker(client, tracker, publisher, clock);

    when(client.post(
            eq("/orders/regular"), any(), eq(PlaceOrderResponseDto.class), eq(Mutability.MUTATION)))
        .thenReturn(
            new PlaceOrderResponseDto(
                "success", new PlaceOrderResponseDto.PlaceOrderDataDto("id-1")));

    broker.placeOrder(makeRequest(Optional.of("caller-verbatim-tag")));

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, String>> formCaptor = ArgumentCaptor.forClass(Map.class);
    verify(client).post(any(), formCaptor.capture(), any(), any());

    assertThat(formCaptor.getValue()).containsEntry("tag", "caller-verbatim-tag");
  }

  @Test
  void ambiguousOrderStatePublishesCriticalAlertAndRethrows() {
    final KiteHttpClient client = mock(KiteHttpClient.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final KiteBroker broker = new KiteBroker(client, tracker, publisher, clock);

    final AmbiguousOrderStateException ambiguousEx =
        new AmbiguousOrderStateException(
            "/orders/regular", "tag-ambig-1", "Timeout after socket write");

    when(client.post(
            eq("/orders/regular"), any(), eq(PlaceOrderResponseDto.class), eq(Mutability.MUTATION)))
        .thenThrow(ambiguousEx);

    assertThatThrownBy(() -> broker.placeOrder(makeRequest(Optional.of("tag-ambig-1"))))
        .isSameAs(ambiguousEx);

    assertThat(published).hasSize(1);
    final Notification alert = published.get(0);
    assertThat(alert.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(alert.type()).isEqualTo(NotificationType.ORDER_AMBIGUOUS);
    assertThat(alert.title()).contains("tag-ambig-1");
  }

  @Test
  void orderBookMapsAllStatusRowsFromDtoToDomain() {
    final KiteHttpClient client = mock(KiteHttpClient.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final NotificationPublisher publisher = n -> {};
    final KiteBroker broker = new KiteBroker(client, tracker, publisher, clock);

    final List<OrderDto> dtos =
        List.of(
            new OrderDto(
                "1",
                "tag1",
                "NSE",
                "RELIANCE",
                "BUY",
                "LIMIT",
                "MIS",
                "DAY",
                "OPEN",
                10,
                0,
                10,
                BigDecimal.valueOf(2500),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "",
                "2026-10-23 10:00:00"),
            new OrderDto(
                "2",
                "tag2",
                "NSE",
                "TCS",
                "SELL",
                "MARKET",
                "CNC",
                "DAY",
                "COMPLETE",
                5,
                5,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(3500),
                "",
                "2026-10-23 10:00:01"),
            new OrderDto(
                "3",
                "tag3",
                "NFO",
                "NIFTY26",
                "BUY",
                "SL",
                "NRML",
                "DAY",
                "TRIGGER PENDING",
                50,
                0,
                50,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.ZERO,
                "",
                "2026-10-23 10:00:02"),
            new OrderDto(
                "4",
                "tag4",
                "NSE",
                "INFY",
                "BUY",
                "LIMIT",
                "MIS",
                "DAY",
                "REJECTED",
                20,
                0,
                0,
                BigDecimal.valueOf(1500),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "Insufficient funds",
                "2026-10-23 10:00:03"));

    when(client.get("/orders", OrderBookDto.class, Mutability.READ))
        .thenReturn(new OrderBookDto("success", dtos));

    final List<Order> orders = broker.orderBook();

    assertThat(orders).hasSize(4);
    assertThat(orders.get(0).status()).isEqualTo(OrderStatus.OPEN);
    assertThat(orders.get(1).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(orders.get(2).status()).isEqualTo(OrderStatus.OPEN);
    assertThat(orders.get(2).rawStatus()).isEqualTo("TRIGGER PENDING");
    assertThat(orders.get(3).status()).isEqualTo(OrderStatus.REJECTED);
    assertThat(orders.get(3).statusMessage()).isEqualTo("Insufficient funds");
  }

  @Test
  void positionsHoldingsMarginsQuotesMapFieldExact() {
    final KiteHttpClient client = mock(KiteHttpClient.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final NotificationPublisher publisher = n -> {};
    final KiteBroker broker = new KiteBroker(client, tracker, publisher, clock);

    // Positions mapping
    when(client.get("/portfolio/positions", PositionDto.class, Mutability.READ))
        .thenReturn(
            new PositionDto(
                "success",
                new PositionDto.PositionDataDto(
                    List.of(
                        new PositionDto.PositionItemDto(
                            "NSE",
                            "RELIANCE",
                            "MIS",
                            10,
                            0,
                            1,
                            BigDecimal.valueOf(2500),
                            BigDecimal.valueOf(150))),
                    List.of())));

    final List<Position> positions = broker.positions();
    assertThat(positions).hasSize(1);
    assertThat(positions.get(0).tradingsymbol()).isEqualTo("RELIANCE");
    assertThat(positions.get(0).pnl()).isEqualByComparingTo(BigDecimal.valueOf(150));

    // Holdings mapping
    when(client.get("/portfolio/holdings", HoldingDto.class, Mutability.READ))
        .thenReturn(
            new HoldingDto(
                "success",
                List.of(
                    new HoldingDto.HoldingItemDto(
                        "NSE",
                        "INFY",
                        50,
                        10,
                        BigDecimal.valueOf(1400),
                        BigDecimal.valueOf(1450),
                        BigDecimal.valueOf(2500)))));

    final List<Holding> holdings = broker.holdings();
    assertThat(holdings).hasSize(1);
    assertThat(holdings.get(0).tradingsymbol()).isEqualTo("INFY");
    assertThat(holdings.get(0).t1Quantity()).isEqualTo(10);

    // Margins mapping
    when(client.get("/user/margins", MarginsDto.class, Mutability.READ))
        .thenReturn(
            new MarginsDto(
                "success",
                Map.of("equity", Map.of("net", 50000.0, "available", Map.of("cash", 45000.0)))));

    final Margins margins = broker.margins();
    assertThat(margins).isNotNull();

    // Quotes mapping
    final QuoteRef ref = new QuoteRef(Exchange.NSE, "NIFTY 50", new InstrumentToken(256265L));
    when(client.get(eq("/quote?i=NSE%3ANIFTY+50"), any(), eq(Mutability.READ)))
        .thenReturn(
            new QuoteDto(
                "success",
                Map.of(
                    "NSE:NIFTY 50",
                    new QuoteDataDto(
                        256265L,
                        BigDecimal.valueOf(2550),
                        new QuoteDataDto.QuoteOhlcDto(
                            BigDecimal.valueOf(2500),
                            BigDecimal.valueOf(2570),
                            BigDecimal.valueOf(2490),
                            BigDecimal.valueOf(2480)),
                        100000L))));

    final Map<InstrumentToken, Quote> quotes = broker.quotes(List.of(ref));
    assertThat(quotes).containsKey(new InstrumentToken(256265L));
    final Quote q = quotes.get(new InstrumentToken(256265L));
    assertThat(q.lastPrice()).isEqualByComparingTo(BigDecimal.valueOf(2550));
    assertThat(q.tradingsymbol()).isEqualTo("NIFTY 50");
  }
}
