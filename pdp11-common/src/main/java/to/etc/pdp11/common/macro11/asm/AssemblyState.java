package to.etc.pdp11.common.macro11.asm;

import java.util.EnumSet;
import java.util.Set;

/**
 * The state that the parsers need besides their input: the radix, the {@code .ENABL} options,
 * the symbols, and how to evaluate an expression.
 */
final class AssemblyState {
	/**
	 * The options of {@code .ENABL} and {@code .DSABL}.
	 *
	 * <p>Only some change what this assembler does; the rest are about output it does not make
	 * - cross references, card readers, the binary file - and are accepted so that sources
	 * written for DEC's assembler assemble here. Those that would change the code are warned
	 * about, because the code here will not be what they asked for.</p>
	 */
	enum Option {
		/** Absolute rather than PC-relative addressing for a plain address. */
		AMA(true),

		/** A local symbol block that ordinary labels do not end. */
		LSB(true),

		/** Undefined symbols are external. Without a linker they are an error regardless. */
		GBL(true),

		/** Lower case in strings. Always kept here. */
		LC(true),

		/** Lower case in .IF IDN/DIF. Always compared as written here. */
		LCM(true),

		/** An unknown operation looks for a macro of that name, as if it had been .MCALLed. */
		MCL(true),

		/** Absolute binary output. There is no output file. */
		ABS(true),

		/** Cross reference. */
		CRF(true),

		/** Register definitions; they are always there. */
		REG(true),

		/** Columns 73-80 are a comment, as on cards. */
		CDR(false),

		/** Truncate rather than round floating-point numbers. */
		FPT(false),

		/** No binary output for the statements. */
		PNC(false);

		private final boolean m_supported;

		Option(boolean supported) {
			m_supported = supported;
		}

		/** Whether it does here what it does in DEC's assembler, or makes no difference here. */
		boolean isSupported() {
			return m_supported;
		}

		static Option find(String name) {
			for(Option o : values()) {
				if(o.name().equals(name))
					return o;
			}
			return null;
		}
	}

	private final SymbolTable m_symbols = new SymbolTable();

	private final Diagnostics m_diagnostics;

	private final Evaluator m_evaluator;

	private final Set<Option> m_enabled = EnumSet.of(Option.GBL, Option.LC, Option.LCM, Option.REG);

	private int m_radix = 8;

	AssemblyState(Diagnostics diagnostics) {
		m_diagnostics = diagnostics;
		m_evaluator = new Evaluator(m_symbols, diagnostics);
	}

	SymbolTable getSymbols() {
		return m_symbols;
	}

	Diagnostics getDiagnostics() {
		return m_diagnostics;
	}

	Evaluator getEvaluator() {
		return m_evaluator;
	}

	int getRadix() {
		return m_radix;
	}

	void setRadix(int radix) {
		m_radix = radix;
	}

	boolean isEnabled(Option o) {
		return m_enabled.contains(o);
	}

	void setEnabled(Option o, boolean on) {
		if(on)
			m_enabled.add(o);
		else
			m_enabled.remove(o);
	}
}
