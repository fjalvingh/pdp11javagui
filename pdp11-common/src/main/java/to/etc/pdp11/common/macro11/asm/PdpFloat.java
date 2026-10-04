package to.etc.pdp11.common.macro11.asm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * PDP-11 floating point, for {@code .FLT2}, {@code .FLT4} and {@code ^F}.
 *
 * <p>The format is sign, an 8-bit exponent in excess 128, and a fraction normalised to
 * {@code 0.1xxx} in binary whose leading 1 is not stored. One word holds 7 bits of fraction
 * ({@code ^F}), two hold 23 ({@code .FLT2}) and four hold 55 ({@code .FLT4}). Zero is all
 * zeroes. The value is rounded to the nearest representable number.</p>
 *
 * <p>The decimal text is converted with {@link BigDecimal} rather than through a
 * {@code double}, because a {@code double} has 53 bits and {@code .FLT4} needs 56.</p>
 */
final class PdpFloat {
	private static final MathContext PRECISION = new MathContext(40);

	private PdpFloat() {
	}

	/**
	 * Encode a decimal number in {@code words} words: 1, 2 or 4.
	 *
	 * @throws AsmException when the text is not a number or the value is out of range
	 */
	static int[] parse(String text, int words, Location at) throws AsmException {
		BigDecimal value;
		try {
			value = new BigDecimal(text.trim());
		} catch(NumberFormatException x) {
			throw new AsmException(at, "'" + text + "' is not a floating-point number");
		}
		return encode(value, words, at);
	}

	static int[] encode(BigDecimal value, int words, Location at) throws AsmException {
		if(words != 1 && words != 2 && words != 4)
			throw new IllegalArgumentException("A floating-point number has 1, 2 or 4 words, not " + words);
		int[] result = new int[words];
		if(value.signum() == 0)
			return result;

		boolean negative = value.signum() < 0;
		BigDecimal magnitude = value.abs();

		//-- Find e with 0.5 <= magnitude / 2^e < 1.
		int exponent = magnitude.unscaledValue().bitLength() - (int) Math.ceil(magnitude.scale() * Math.log(10) / Math.log(2));
		BigDecimal fraction = scale(magnitude, -exponent);
		while(fraction.compareTo(BigDecimal.ONE) >= 0) {
			exponent++;
			fraction = scale(magnitude, -exponent);
		}
		while(fraction.compareTo(new BigDecimal("0.5")) < 0) {
			exponent--;
			fraction = scale(magnitude, -exponent);
		}

		//-- The fraction as an integer of 8 + 16*(words-1) bits, the top one being the hidden 1.
		int bits = 8 + 16 * (words - 1);
		BigInteger mantissa = fraction.multiply(new BigDecimal(BigInteger.ONE.shiftLeft(bits)))
			.setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact();
		if(mantissa.bitLength() > bits) {
			//-- Rounding carried into a new top bit: 0.1111... became 1.000...
			mantissa = mantissa.shiftRight(1);
			exponent++;
		}

		int biased = exponent + 128;
		if(biased <= 0)
			throw new AsmException(at, value.toPlainString() + " is too small for PDP-11 floating point");
		if(biased > 255)
			throw new AsmException(at, value.toPlainString() + " is too large for PDP-11 floating point");

		long m = mantissa.longValue();
		int shift = bits - 8;
		result[0] = (negative ? 0x8000 : 0) | (biased << 7) | (int) ((m >>> shift) & 0x7F);
		for(int i = 1; i < words; i++) {
			shift -= 16;
			result[i] = (int) ((m >>> shift) & 0xFFFF);
		}
		return result;
	}

	private static BigDecimal scale(BigDecimal v, int power) {
		if(power >= 0)
			return v.multiply(new BigDecimal(BigInteger.ONE.shiftLeft(power)));
		return v.divide(new BigDecimal(BigInteger.ONE.shiftLeft(-power)), PRECISION);
	}
}
