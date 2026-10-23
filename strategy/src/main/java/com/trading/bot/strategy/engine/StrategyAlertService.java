package com.trading.bot.strategy.engine;

import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import com.trading.bot.strategy.config.ExecutionMode;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.TradeLeg;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Dispatches detailed execution, stop-loss, and final P&L notifications to Telegram. */
@Service
public final class StrategyAlertService {

  private final NotificationPublisher publisher;

  public StrategyAlertService(NotificationPublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
  }

  /**
   * Notifies Telegram when 09:45 OTM hedges are executed (PAPER and LIVE).
   *
   * @param record trade record
   * @param spot index spot price
   * @param mode execution mode
   */
  public void notifyHedgeExecution(DailyTradeRecord record, double spot, ExecutionMode mode) {
    final String modeBadge = "[" + mode.name() + "]";
    final String title =
        String.format(Locale.ROOT, "%s %s 09:45 Hedges Executed", modeBadge, record.index().name());

    final String body =
        String.format(
            Locale.ROOT,
            "• Strategy: 0/1 DTE Intraday Option Selling%n"
                + "• Mode: %s%n"
                + "• Target: %s (Spot: %.2f)%n"
                + "• Long CE: %s @ ₹%s%n"
                + "• Long PE: %s @ ₹%s%n"
                + "• Quantity: %d (%s exchange)",
            mode.name(),
            record.index().name(),
            spot,
            record.hedgeCe().symbol(),
            record.hedgeCe().entryPrice().toPlainString(),
            record.hedgePe().symbol(),
            record.hedgePe().entryPrice().toPlainString(),
            record.hedgeCe().quantity(),
            record.index().spotExchange().name());

    publisher.publish(
        Notification.of(Severity.INFO, NotificationType.ORDER_SUBMITTED, title, body));
  }

  /**
   * Notifies Telegram when 10:00 ATM straddle is sold (PAPER and LIVE).
   *
   * @param record trade record
   * @param spot index spot price
   * @param mode execution mode
   */
  public void notifyStraddleExecution(DailyTradeRecord record, double spot, ExecutionMode mode) {
    final String modeBadge = "[" + mode.name() + "]";
    final String title =
        String.format(
            Locale.ROOT, "%s %s 10:00 ATM Straddle Sold", modeBadge, record.index().name());

    final String body =
        String.format(
            Locale.ROOT,
            "• Strategy: 0/1 DTE Intraday Option Selling%n"
                + "• Mode: %s%n"
                + "• Target: %s (Spot: %.2f)%n"
                + "• Short CE: %s @ ₹%s (SL: ₹%s)%n"
                + "• Short PE: %s @ ₹%s (SL: ₹%s)%n"
                + "• Quantity: %d",
            mode.name(),
            record.index().name(),
            spot,
            record.shortCe().symbol(),
            record.shortCe().entryPrice().toPlainString(),
            record.shortCe().slPrice().toPlainString(),
            record.shortPe().symbol(),
            record.shortPe().entryPrice().toPlainString(),
            record.shortPe().slPrice().toPlainString(),
            record.shortCe().quantity());

    publisher.publish(
        Notification.of(Severity.INFO, NotificationType.ORDER_SUBMITTED, title, body));
  }

  /**
   * Notifies Telegram when a short leg's 30% stop-loss is hit.
   *
   * @param stoppedLeg leg that triggered SL
   * @param exitPrice fill price at exit
   * @param survivingLeg counter leg whose SL was trailed
   * @param mode execution mode
   */
  public void notifyStopLossHit(
      TradeLeg stoppedLeg, BigDecimal exitPrice, TradeLeg survivingLeg, ExecutionMode mode) {
    final String modeBadge = "[" + mode.name() + "]";
    final String title =
        String.format(Locale.ROOT, "%s Stop-Loss Hit: %s", modeBadge, stoppedLeg.symbol());

    final BigDecimal safeExit = exitPrice != null ? exitPrice : stoppedLeg.slPrice();
    final BigDecimal loss =
        stoppedLeg
            .entryPrice()
            .subtract(safeExit)
            .multiply(BigDecimal.valueOf(stoppedLeg.quantity()));

    final String trailMsg =
        survivingLeg != null && survivingLeg.isOpen()
            ? String.format(
                Locale.ROOT,
                "• Surviving Leg: %s SL trailed to Breakeven @ ₹%s%n",
                survivingLeg.symbol(),
                survivingLeg.slPrice().toPlainString())
            : "";

    final String body =
        String.format(
            Locale.ROOT,
            "• Leg Exited: %s%n"
                + "• Entry Price: ₹%s%n"
                + "• Exit Price: ₹%s (+30%% SL)%n"
                + "• Leg Loss: ₹%s%n"
                + "%s"
                + "• Wing Hedges: Active until 15:00 IST",
            stoppedLeg.symbol(),
            stoppedLeg.entryPrice().toPlainString(),
            safeExit.toPlainString(),
            loss.toPlainString(),
            trailMsg);

    publisher.publish(Notification.of(Severity.WARN, NotificationType.SYSTEM_ALERT, title, body));
  }

  /**
   * Notifies Telegram when a surviving leg exits at breakeven.
   *
   * @param leg surviving leg that hit breakeven
   * @param exitPrice exit fill price
   * @param mode execution mode
   */
  public void notifyBreakevenHit(TradeLeg leg, BigDecimal exitPrice, ExecutionMode mode) {
    final String modeBadge = "[" + mode.name() + "]";
    final String title =
        String.format(Locale.ROOT, "%s Breakeven Stop Hit: %s", modeBadge, leg.symbol());

    final BigDecimal safeExit = exitPrice != null ? exitPrice : leg.entryPrice();
    final String body =
        String.format(
            Locale.ROOT,
            "• Leg Exited: %s%n"
                + "• Entry Price: ₹%s%n"
                + "• Exit Price: ₹%s (Breakeven Cost)%n"
                + "• Leg P&L: ₹0.00%n"
                + "• Wing Hedges: Active until 15:00 IST",
            leg.symbol(),
            leg.entryPrice().toPlainString(),
            safeExit.toPlainString());

    publisher.publish(Notification.of(Severity.WARN, NotificationType.SYSTEM_ALERT, title, body));
  }

  /**
   * Notifies Telegram at 15:00 IST with the complete daily square-off and final realized P&L.
   *
   * @param record completed daily trade record
   * @param totalPnl final cumulative P&L
   * @param mode execution mode
   */
  public void notifyFinalSquareOff(
      DailyTradeRecord record, BigDecimal totalPnl, ExecutionMode mode) {
    final String modeBadge = "[" + mode.name() + "]";
    final String title =
        String.format(
            Locale.ROOT, "%s %s Daily Square-Off & Final P&L", modeBadge, record.index().name());

    final BigDecimal shortCePnl =
        record.shortCe() != null ? record.shortCe().calculatePnl(true) : BigDecimal.ZERO;
    final BigDecimal shortPePnl =
        record.shortPe() != null ? record.shortPe().calculatePnl(true) : BigDecimal.ZERO;
    final BigDecimal hedgeCePnl =
        record.hedgeCe() != null ? record.hedgeCe().calculatePnl(false) : BigDecimal.ZERO;
    final BigDecimal hedgePePnl =
        record.hedgePe() != null ? record.hedgePe().calculatePnl(false) : BigDecimal.ZERO;

    final String body =
        String.format(
            Locale.ROOT,
            "• Strategy: 0/1 DTE Intraday Option Selling%n"
                + "• Mode: %s%n"
                + "• Target: %s (%s)%n%n"
                + "Leg Performance:%n"
                + "• Short CE: Entry ₹%s -> Exit ₹%s | P&L: ₹%s%n"
                + "• Short PE: Entry ₹%s -> Exit ₹%s | P&L: ₹%s%n"
                + "• Hedge CE: Entry ₹%s -> Exit ₹%s | P&L: ₹%s%n"
                + "• Hedge PE: Entry ₹%s -> Exit ₹%s | P&L: ₹%s%n%n"
                + "==============================%n"
                + "💰 Total Realized P&L: ₹%s%n"
                + "==============================%n"
                + "All legs squared off. Position flat for the day.",
            mode.name(),
            record.index().name(),
            record.date().toString(),
            formatLegEntry(record.shortCe()),
            formatLegExit(record.shortCe()),
            shortCePnl.toPlainString(),
            formatLegEntry(record.shortPe()),
            formatLegExit(record.shortPe()),
            shortPePnl.toPlainString(),
            formatLegEntry(record.hedgeCe()),
            formatLegExit(record.hedgeCe()),
            hedgeCePnl.toPlainString(),
            formatLegEntry(record.hedgePe()),
            formatLegExit(record.hedgePe()),
            hedgePePnl.toPlainString(),
            totalPnl.toPlainString());

    publisher.publish(Notification.of(Severity.INFO, NotificationType.STARTUP, title, body));
  }

  private String formatLegEntry(TradeLeg leg) {
    return leg != null ? leg.entryPrice().toPlainString() : "0.00";
  }

  private String formatLegExit(TradeLeg leg) {
    return leg != null && leg.exitPrice() != null ? leg.exitPrice().toPlainString() : "0.00";
  }
}
