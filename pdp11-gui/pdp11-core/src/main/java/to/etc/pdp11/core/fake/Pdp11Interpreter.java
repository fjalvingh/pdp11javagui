package to.etc.pdp11.core.fake;

/**
 * Executes PDP-11 instructions: the basic instruction set of an 11/05, and nothing more.
 *
 * <p>The fakes do not run programs - a "run" is a timer and a made-up halt. This exists for the
 * programs this application deposits and starts <i>itself</i>, of which the M9312 fast loader is
 * the first: code that has to be right on a real machine the first time, and that otherwise
 * nothing would execute before it got there. A fake that recognises one of them runs it through
 * this, against its own memory, so every build executes the actual words that will be deposited.</p>
 *
 * <p>What is here: the double-operand instructions ({@code MOV CMP BIT BIC BIS ADD SUB} and their
 * byte forms), the single-operand ones ({@code CLR COM INC DEC NEG ADC SBC TST ROR ROL ASR ASL
 * SWAB} and byte forms), every branch, {@code JMP JSR RTS}, the condition code operators,
 * {@code HALT} and {@code RESET}, in all eight addressing modes. What is not, and stops with an
 * {@link Unsupported}: traps and interrupts ({@code EMT TRAP IOT BPT RTI RTT WAIT}), and what an
 * 11/05 does not have either ({@code SOB XOR MARK SXT MUL} and the rest of EIS, floating point,
 * {@code MFPI}). A program of ours that uses one of those is a program that will not run on
 * the machine it is for, which is exactly what this should say.</p>
 *
 * <p>Not thread-safe; the fake that owns it calls it under its own monitor.</p>
 */
public final class Pdp11Interpreter {
	/** Word access to the machine; addresses are even, 16 bits. */
	public interface Bus {
		int readWord(int address);

		void writeWord(int address, int value);
	}

	/** An instruction this interpreter does not execute. */
	public static final class Unsupported extends RuntimeException {
		Unsupported(String message) {
			super(message);
		}
	}

	/** What would trap through vector 4 on a real machine: an odd address, or no memory there. */
	public static final class BusError extends RuntimeException {
		public BusError(String message) {
			super(message);
		}
	}

	private final Bus m_bus;

	private final int[] m_r = new int[8];

	private boolean m_n;

	private boolean m_z;

	private boolean m_v;

	private boolean m_c;

	private boolean m_halted;

	public Pdp11Interpreter(Bus bus) {
		m_bus = bus;
	}

	public int getRegister(int r) {
		return m_r[r];
	}

	public void setRegister(int r, int value) {
		m_r[r] = value & 0xFFFF;
	}

	public int getPc() {
		return m_r[7];
	}

	public void setPc(int pc) {
		m_r[7] = pc & 0xFFFF;
	}

	public boolean isHalted() {
		return m_halted;
	}

	/** The condition codes as the low four bits of the PSW: N Z V C. */
	public int getConditionCodes() {
		return (m_n ? 010 : 0) | (m_z ? 4 : 0) | (m_v ? 2 : 0) | (m_c ? 1 : 0);
	}

	// -------------------------------------------------------------------------------------
	// Memory and operands
	// -------------------------------------------------------------------------------------

	private int readWord(int a) {
		if((a & 1) != 0)
			throw new BusError("odd address " + Integer.toOctalString(a));
		return m_bus.readWord(a) & 0xFFFF;
	}

	private void writeWord(int a, int v) {
		if((a & 1) != 0)
			throw new BusError("odd address " + Integer.toOctalString(a));
		m_bus.writeWord(a, v & 0xFFFF);
	}

	private int readByte(int a) {
		int w = readWord(a & ~1);
		return (a & 1) == 0 ? w & 0xFF : w >> 8;
	}

	private void writeByte(int a, int v) {
		int w = readWord(a & ~1);
		w = (a & 1) == 0 ? (w & 0xFF00) | (v & 0xFF) : (w & 0x00FF) | ((v & 0xFF) << 8);
		writeWord(a & ~1, w);
	}

	private int fetch() {
		int w = readWord(m_r[7]);
		m_r[7] = (m_r[7] + 2) & 0xFFFF;
		return w;
	}

	/**
	 * The operand a six-bit specifier names: a register as {@code ~r}, which is negative, or a
	 * memory address. Any side effect on the register happens here, once.
	 */
	private int operand(int spec, boolean isByte) {
		int mode = (spec >> 3) & 7;
		int reg = spec & 7;
		int inc = isByte && reg < 6 ? 1 : 2;
		switch(mode) {
			case 0:
				return ~reg;
			case 1:
				return m_r[reg];
			case 2: {
				int a = m_r[reg];
				m_r[reg] = (a + inc) & 0xFFFF;
				return a;
			}
			case 3: {
				int a = m_r[reg];
				m_r[reg] = (a + 2) & 0xFFFF;
				return readWord(a);
			}
			case 4:
				m_r[reg] = (m_r[reg] - inc) & 0xFFFF;
				return m_r[reg];
			case 5:
				m_r[reg] = (m_r[reg] - 2) & 0xFFFF;
				return readWord(m_r[reg]);
			case 6: {
				int x = fetch();
				return (x + m_r[reg]) & 0xFFFF;
			}
			default: {
				int x = fetch();
				return readWord((x + m_r[reg]) & 0xFFFF);
			}
		}
	}

	private int read(int operand, boolean isByte) {
		if(operand < 0) {
			int v = m_r[~operand];
			return isByte ? v & 0xFF : v;
		}
		return isByte ? readByte(operand) : readWord(operand);
	}

	private void write(int operand, int value, boolean isByte) {
		if(operand < 0) {
			int r = ~operand;
			m_r[r] = isByte ? (m_r[r] & 0xFF00) | (value & 0xFF) : value & 0xFFFF;
		} else if(isByte) {
			writeByte(operand, value);
		} else {
			writeWord(operand, value);
		}
	}

	private void setNz(int value, boolean isByte) {
		int mask = isByte ? 0xFF : 0xFFFF;
		int sign = isByte ? 0x80 : 0x8000;
		m_n = (value & sign) != 0;
		m_z = (value & mask) == 0;
	}

	// -------------------------------------------------------------------------------------
	// Execution
	// -------------------------------------------------------------------------------------

	/** Execute one instruction. */
	public void step() {
		if(m_halted)
			throw new IllegalStateException("The processor is halted");
		int pc = m_r[7];
		int op = fetch();
		int group = (op >> 12) & 7;
		boolean isByte = (op & 0100000) != 0;
		if(group >= 1 && group <= 6) {
			doubleOperand(group, isByte, op);
			return;
		}
		if(group == 7)
			throw unsupported(op, pc);

		if(!isByte) {
			if(op == 0) {
				m_halted = true;
				m_r[7] = pc + 2;
			} else if(op == 5) {
				//-- RESET: the bus initialise has nothing to do in here.
			} else if(op < 0100) {
				throw unsupported(op, pc);
			} else if(op < 0200) {
				int dst = operand(op & 077, false);
				if(dst < 0)
					throw new BusError("JMP to a register");
				m_r[7] = dst;
			} else if(op < 0210) {
				int r = op & 7;
				m_r[7] = m_r[r];
				m_r[r] = readWord(m_r[6]);
				m_r[6] = (m_r[6] + 2) & 0xFFFF;
			} else if(op < 0240) {
				throw unsupported(op, pc);
			} else if(op < 0300) {
				boolean set = (op & 020) != 0;
				if((op & 010) != 0)
					m_n = set;
				if((op & 4) != 0)
					m_z = set;
				if((op & 2) != 0)
					m_v = set;
				if((op & 1) != 0)
					m_c = set;
			} else if(op < 0400) {
				int dst = operand(op & 077, false);
				int v = read(dst, false);
				v = ((v >> 8) | (v << 8)) & 0xFFFF;
				write(dst, v, false);
				setNz(v, true);
				m_v = false;
				m_c = false;
			} else if(op < 04000) {
				branch(op, signedBranch((op >> 8) & 7));
			} else if(op < 05000) {
				int r = (op >> 6) & 7;
				int dst = operand(op & 077, false);
				if(dst < 0)
					throw new BusError("JSR to a register");
				m_r[6] = (m_r[6] - 2) & 0xFFFF;
				writeWord(m_r[6], m_r[r]);
				m_r[r] = m_r[7];
				m_r[7] = dst;
			} else if(op < 06400) {
				singleOperand((op >> 6) & 077, false, op, pc);
			} else {
				throw unsupported(op, pc);
			}
			return;
		}
		int low = op & 077777;
		if(low < 04000)
			branch(op, unsignedBranch((op >> 8) & 7));
		else if(low >= 05000 && low < 06400)
			singleOperand((op >> 6) & 077, true, op, pc);
		else
			throw unsupported(op, pc);
	}

	private void branch(int op, boolean taken) {
		if(taken) {
			int off = (byte) (op & 0xFF);
			m_r[7] = (m_r[7] + 2 * off) & 0xFFFF;
		}
	}

	/** BR BNE BEQ BGE BLT BGT BLE, by bits 8..10; 0 is not a branch and never reached. */
	private boolean signedBranch(int code) {
		boolean nv = m_n ^ m_v;
		return switch(code) {
			case 1 -> true;
			case 2 -> !m_z;
			case 3 -> m_z;
			case 4 -> !nv;
			case 5 -> nv;
			case 6 -> !(m_z || nv);
			case 7 -> m_z || nv;
			default -> throw new IllegalStateException();
		};
	}

	/** BPL BMI BHI BLOS BVC BVS BCC BCS. */
	private boolean unsignedBranch(int code) {
		return switch(code) {
			case 0 -> !m_n;
			case 1 -> m_n;
			case 2 -> !(m_c || m_z);
			case 3 -> m_c || m_z;
			case 4 -> !m_v;
			case 5 -> m_v;
			case 6 -> !m_c;
			default -> m_c;
		};
	}

	private void doubleOperand(int group, boolean isByte, int op) {
		boolean subtract = group == 6 && isByte;
		boolean b = isByte && !subtract;
		int mask = b ? 0xFF : 0xFFFF;
		int sign = b ? 0x80 : 0x8000;
		int src = read(operand((op >> 6) & 077, b), b);
		int dst = operand(op & 077, b);
		switch(group) {
			case 1 -> {
				if(b && dst < 0) {
					m_r[~dst] = (byte) src & 0xFFFF;         // MOVB into a register sign-extends
				} else {
					write(dst, src, b);
				}
				setNz(src, b);
				m_v = false;
			}
			case 2 -> {
				int d = read(dst, b);
				int r = (src - d) & mask;
				setNz(r, b);
				m_v = ((src ^ d) & (src ^ r) & sign) != 0;
				m_c = src < d;
			}
			case 3 -> {
				setNz(src & read(dst, b), b);
				m_v = false;
			}
			case 4 -> {
				int r = read(dst, b) & ~src & mask;
				write(dst, r, b);
				setNz(r, b);
				m_v = false;
			}
			case 5 -> {
				int r = (read(dst, b) | src) & mask;
				write(dst, r, b);
				setNz(r, b);
				m_v = false;
			}
			default -> {
				int d = read(dst, false);
				int r;
				if(subtract) {
					r = (d - src) & 0xFFFF;
					m_v = ((d ^ src) & (d ^ r) & 0x8000) != 0;
					m_c = d < src;
				} else {
					r = (d + src) & 0xFFFF;
					m_v = (~(d ^ src) & (d ^ r) & 0x8000) != 0;
					m_c = d + src > 0xFFFF;
				}
				write(dst, r, false);
				setNz(r, false);
			}
		}
	}

	private void singleOperand(int code, boolean b, int op, int pc) {
		int mask = b ? 0xFF : 0xFFFF;
		int sign = b ? 0x80 : 0x8000;
		int dst = operand(op & 077, b);
		int d = read(dst, b);
		int r;
		switch(code) {
			case 050 -> {
				r = 0;
				m_v = false;
				m_c = false;
			}
			case 051 -> {
				r = ~d & mask;
				m_v = false;
				m_c = true;
			}
			case 052 -> {
				r = (d + 1) & mask;
				m_v = d == sign - 1;
			}
			case 053 -> {
				r = (d - 1) & mask;
				m_v = d == sign;
			}
			case 054 -> {
				r = -d & mask;
				m_v = r == sign;
				m_c = r != 0;
			}
			case 055 -> {
				int c = m_c ? 1 : 0;
				r = (d + c) & mask;
				m_v = d == sign - 1 && m_c;
				m_c = d == mask && m_c;
			}
			case 056 -> {
				int c = m_c ? 1 : 0;
				r = (d - c) & mask;
				m_v = d == sign;
				m_c = d == 0 && m_c;
			}
			case 057 -> {
				setNz(d, b);
				m_v = false;
				m_c = false;
				return;                                     // TST writes nothing
			}
			case 060 -> {
				r = (d >> 1) | (m_c ? sign : 0);
				m_c = (d & 1) != 0;
			}
			case 061 -> {
				r = ((d << 1) | (m_c ? 1 : 0)) & mask;
				m_c = (d & sign) != 0;
			}
			case 062 -> {
				r = (d >> 1) | (d & sign);
				m_c = (d & 1) != 0;
			}
			case 063 -> {
				r = (d << 1) & mask;
				m_c = (d & sign) != 0;
			}
			default -> throw unsupported(op, pc);
		}
		write(dst, r, b);
		setNz(r, b);
		if(code >= 060)
			m_v = m_n ^ m_c;
	}

	private static Unsupported unsupported(int op, int pc) {
		return new Unsupported("instruction " + Integer.toOctalString(op) + " at " + Integer.toOctalString(pc)
			+ " is not one this interpreter executes");
	}
}
