package com.trading.bot.notifications.internal;

import com.trading.bot.notifications.api.Notification;

/** Transport port for delivering individual notifications to external channels. */
@FunctionalInterface
public interface NotificationTransport {
  void deliver(Notification n);
}
