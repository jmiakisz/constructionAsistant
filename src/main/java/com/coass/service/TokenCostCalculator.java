package com.coass.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TokenCostCalculator {

    // Ceny per 1M tokenów (USD)
    private static final BigDecimal SONNET_INPUT    = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT   = new BigDecimal("15.00");
    private static final BigDecimal HAIKU_INPUT     = new BigDecimal("0.25");
    private static final BigDecimal HAIKU_OUTPUT    = new BigDecimal("1.25");
    private static final BigDecimal HAIKU_CACHE_READ = new BigDecimal("0.03");

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    public static BigDecimal calculate(String model, int inputTokens, int outputTokens,
                                       int cacheCreationTokens, int cacheReadTokens) {
        boolean isHaiku = model != null && model.contains("haiku");

        BigDecimal inputPrice  = isHaiku ? HAIKU_INPUT  : SONNET_INPUT;
        BigDecimal outputPrice = isHaiku ? HAIKU_OUTPUT : SONNET_OUTPUT;
        BigDecimal cacheReadPrice = isHaiku ? HAIKU_CACHE_READ : new BigDecimal("0.30");

        BigDecimal cost = BigDecimal.ZERO;
        cost = cost.add(inputPrice.multiply(BigDecimal.valueOf(inputTokens)).divide(MILLION, 6, RoundingMode.HALF_UP));
        cost = cost.add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)).divide(MILLION, 6, RoundingMode.HALF_UP));
        cost = cost.add(inputPrice.multiply(BigDecimal.valueOf(cacheCreationTokens)).divide(MILLION, 6, RoundingMode.HALF_UP));
        cost = cost.add(cacheReadPrice.multiply(BigDecimal.valueOf(cacheReadTokens)).divide(MILLION, 6, RoundingMode.HALF_UP));

        return cost;
    }
}
