package com.trading.bot.broker.kite;

/** Access token provider and on-demand session refresher. */
public interface SessionDelegate {
  String accessToken();

  void forceRelogin();
}
