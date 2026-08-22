Prompt for Coding Assistant:

Write a modular, clean, and object-oriented Java 17+ implementation for an Iron Fly trading strategy on Indian equities (RELIANCE, HDFCBANK, TCS).

Design Requirements:

1. Data Models (Use Records or POJOs with Lombok/standard getters):
   - OptionType: Enum (CALL, PUT)
   - OptionLeg: symbol, strike, OptionType, isLong, entryPrice, currentPrice, delta, lotSize.
     * Method: getDecayPercentage() -> (entryPrice - currentPrice) / entryPrice * 100.0 (for short legs)
     * Method: getMtmPnl() -> calculates current P&L based on long/short orientation and lot size.
   - IronFlyPosition: underlyingSymbol, shortCall, shortPut, longCallHedge, longPutHedge, entrySpotPrice, netCredit, totalLotSize, status (ACTIVE, CLOSED, ADJUSTED).
     * Method: getTotalMtm()
     * Method: getUpperBreakeven() -> atmStrike + netCredit
     * Method: getLowerBreakeven() -> atmStrike - netCredit

2. Core Strategy Logic:
   - Initial Setup:
     * Identify ATM strike closest to spot.
     * Short ATM Call + Short ATM Put.
     * Initial Straddle Premium = shortCall.entryPrice + shortPut.entryPrice.
     * Long Call Hedge at (ATM + Straddle Premium), Long Put Hedge at (ATM - Straddle Premium).
     * Calculate Net Credit = (Short CE + Short PE) - (Long CE + Long PE).
   
3. Daily Evaluation Engine (DailyAnalyzer):
   - Run a daily check on open positions:
     * Check Profit Target: If Total MTM >= 50% of Net Credit -> Trigger FULL_EXIT_TARGET.
     * Check Stop Loss: If Total MTM <= -100% of Net Credit -> Trigger FULL_EXIT_SL.
     * Expiry Guard: If Days to Expiry <= 4 -> Trigger FULL_EXIT_EXPIRY.
     * 70% Decay Trigger:
       - If shortCall.getDecayPercentage() >= 70.0 -> Trigger ADJUST_CALL_SIDE.
       - If shortPut.getDecayPercentage() >= 70.0 -> Trigger ADJUST_PUT_SIDE.

4. Adjustment Engine (AdjustmentHandler):
   - When 70% decay is hit on the Put side:
     * Close shortPut and longPutHedge.
     * Select a new Put strike closer to current spot (~25-30 Delta).
     * Buy new OTM Put hedge.
     * Update net credit and composite position structure.
   - When 70% decay is hit on the Call side:
     * Close shortCall and longCallHedge.
     * Select a new Call strike closer to current spot (~25-30 Delta).
     * Buy new OTM Call hedge.
     * Update net credit and composite position structure.

5. Code Quality:
   - Provide a working main() class simulating a 30-day lifecycle with price ticks.
   - Use BigDecimal or Double for financial calculations.
   - Separate broker API data fetching using an interface (e.g., MarketDataProvider).