package com.tradingbot.backtest;

/**
 * Black-Scholes European option pricer for backtesting.
 * No external dependencies — pure math.
 */
public final class BlackScholesPricer {

    private BlackScholesPricer() {}

    /**
     * Calculates Black-Scholes call price.
     */
    public static double callPrice(double spot, double strike, double timeToExpiryYears,
                                    double riskFreeRate, double volatility) {
        double d1 = d1(spot, strike, timeToExpiryYears, riskFreeRate, volatility);
        double d2 = d2(spot, strike, timeToExpiryYears, riskFreeRate, volatility);
        return spot * normalCdf(d1) - strike * Math.exp(-riskFreeRate * timeToExpiryYears) * normalCdf(d2);
    }

    /**
     * Calculates Black-Scholes put price.
     */
    public static double putPrice(double spot, double strike, double timeToExpiryYears,
                                   double riskFreeRate, double volatility) {
        double d1 = d1(spot, strike, timeToExpiryYears, riskFreeRate, volatility);
        double d2 = d2(spot, strike, timeToExpiryYears, riskFreeRate, volatility);
        return strike * Math.exp(-riskFreeRate * timeToExpiryYears) * normalCdf(-d2) - spot * normalCdf(-d1);
    }

    /**
     * Calculates put-call parity: C - P = S - K * e^(-rT)
     */
    public static double putFromCall(double spot, double strike, double timeToExpiryYears,
                                      double riskFreeRate, double callPrice) {
        return callPrice - spot + strike * Math.exp(-riskFreeRate * timeToExpiryYears);
    }

    /**
     * Calculates delta of a call option.
     */
    public static double callDelta(double spot, double strike, double timeToExpiryYears,
                                    double riskFreeRate, double volatility) {
        return normalCdf(d1(spot, strike, timeToExpiryYears, riskFreeRate, volatility));
    }

    /**
     * Calculates delta of a put option.
     */
    public static double putDelta(double spot, double strike, double timeToExpiryYears,
                                   double riskFreeRate, double volatility) {
        return normalCdf(d1(spot, strike, timeToExpiryYears, riskFreeRate, volatility)) - 1.0;
    }

    private static double d1(double spot, double strike, double time, double rate, double vol) {
        return (Math.log(spot / strike) + (rate + vol * vol / 2.0) * time) / (vol * Math.sqrt(time));
    }

    private static double d2(double spot, double strike, double time, double rate, double vol) {
        return d1(spot, strike, time, rate, vol) - vol * Math.sqrt(time);
    }

    private static double normalCdf(double x) {
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;
        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return 0.5 * (1.0 + sign * y);
    }
}
