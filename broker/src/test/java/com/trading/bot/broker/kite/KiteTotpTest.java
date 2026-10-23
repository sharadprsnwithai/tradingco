package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KiteTotpTest {

  private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  private final KiteTotp totp = new KiteTotp();

  @ParameterizedTest
  @CsvSource({
    "59, 287082",
    "1111111109, 081804",
    "1111111111, 050471",
    "1234567890, 005924",
    "2000000000, 279037",
    "20000000000, 353130"
  })
  void rfc6238VectorsMatchLastSixDigits(long epochSeconds, String expectedCode) {
    final String code = totp.code(RFC_SECRET, epochSeconds);
    assertThat(code).isEqualTo(expectedCode);
  }

  @Test
  void sameStepProducesIdenticalCode() {
    final String code1 = totp.code(RFC_SECRET, 59L);
    final String code2 = totp.code(RFC_SECRET, 31L);
    assertThat(code1).isEqualTo(code2);
  }

  @Test
  void differentStepProducesDifferentCode() {
    final String code1 = totp.code(RFC_SECRET, 59L);
    final String code2 = totp.code(RFC_SECRET, 89L);
    assertThat(code1).isNotEqualTo(code2);
  }
}
