package to.etc.pdp11.common.macro11.asm;

/**
 * The value of an expression.
 *
 * <p>Three kinds. An absolute value is a 16-bit number. A relocatable value is an offset into a
 * section that has not been given an address yet; it becomes absolute when the section is
 * placed. A register is what {@code R3} and {@code %3} are, and is only meaningful as an
 * operand.</p>
 */
sealed interface Value permits Value.Absolute, Value.Relocatable, Value.Register {
	/** A plain 16-bit number, held unsigned. */
	record Absolute(int value) implements Value {
		public Absolute {
			value &= 0xFFFF;
		}
	}

	/** An offset into a section. */
	record Relocatable(Section section, int offset) implements Value {
		public Relocatable {
			offset &= 0xFFFF;
		}
	}

	/** One of the eight general registers. */
	record Register(int number) implements Value {
		public Register {
			if(number < 0 || number > 7)
				throw new IllegalArgumentException("Register " + number + " does not exist");
		}
	}

	/** The number this value stands for, once it has one. Registers stand for their number. */
	default int number() {
		return switch(this) {
			case Absolute a -> a.value();
			case Relocatable r -> r.section().addressOf(r.offset());
			case Register r -> r.number();
		};
	}

	/**
	 * The section this value is relative to, or null when it is not relocatable.
	 *
	 * <p>A label in an absolute section is an {@link Absolute} value, so only relocatable
	 * sections ever appear here.</p>
	 */
	default Section relocationSection() {
		return this instanceof Relocatable r ? r.section() : null;
	}
}
