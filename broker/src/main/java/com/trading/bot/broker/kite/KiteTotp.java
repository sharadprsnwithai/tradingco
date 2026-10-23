package com.trading.bot.broker.kite;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** RFC 6238 TOTP code generator using 30-second steps and SHA-1. */
@Component
public final class KiteTotp {

  private static final long TIME_STEP_SECONDS = 30L;
  private static final int DIGIT_COUNT = 6;

  private final CodeGenerator generator;

  public KiteTotp() {
    this(new DefaultCodeGenerator(HashingAlgorithm.SHA1, DIGIT_COUNT));
  }

  public KiteTotp(CodeGenerator generator) {
    this.generator = Objects.requireNonNull(generator, "generator must not be null");
  }

  /**
   * Generates a 6-digit TOTP code for the given base32 secret and epoch timestamp.
   *
   * @param base32Secret base32 encoded secret key
   * @param epochSeconds current timestamp in epoch seconds
   * @return 6-digit TOTP string
   */
  public String code(String base32Secret, long epochSeconds) {
    Objects.requireNonNull(base32Secret, "base32Secret must not be null");
    try {
      final long counter = Math.floorDiv(epochSeconds, TIME_STEP_SECONDS);
      return generator.generate(base32Secret, counter);
    } catch (CodeGenerationException e) {
      throw new IllegalArgumentException("Failed to generate TOTP from secret", e);
    }
  }
}
