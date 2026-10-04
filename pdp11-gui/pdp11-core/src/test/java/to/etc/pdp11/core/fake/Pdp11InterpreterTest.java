package to.etc.pdp11.core.fake;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.common.macro11.asm.AssemblyResult;
import to.etc.pdp11.common.macro11.asm.Macro11Assembler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interpreter, on the points where a PDP-11 is easy to get wrong - byte operations, sign
 * extension, auto-increment by one or two, the carry - each with a small program assembled by
 * the project's own assembler and run to its HALT.
 */
class Pdp11InterpreterTest {
	private final int[] m_mem = new int[0100000 / 2];

	private Pdp11Interpreter run(String source) {
		AssemblyResult r = new Macro11Assembler().assemble("t", "\t.ASECT\n\t.=1000\n" + source + "\n\tHALT\n\t.END\n");
		assertFalse(r.hasErrors(), () -> r.getErrors().toString());
		for(AssemblyResult.Word w : r.getWords()) {
			m_mem[w.address() / 2] = w.value() & 0xFFFF;
		}
		Pdp11Interpreter cpu = new Pdp11Interpreter(new Pdp11Interpreter.Bus() {
			@Override
			public int readWord(int address) {
				if(address >= 0100000)
					throw new Pdp11Interpreter.BusError("nonexistent " + Integer.toOctalString(address));
				return m_mem[address / 2];
			}

			@Override
			public void writeWord(int address, int value) {
				if(address >= 0100000)
					throw new Pdp11Interpreter.BusError("nonexistent " + Integer.toOctalString(address));
				m_mem[address / 2] = value;
			}
		});
		cpu.setPc(01000);
		cpu.setRegister(6, 01000);
		for(int n = 0; !cpu.isHalted(); n++) {
			assertTrue(n < 10000, "runaway");
			cpu.step();
		}
		return cpu;
	}

	@Test
	void movbIntoARegisterSignExtendsAndIntoMemoryDoesNot() {
		Pdp11Interpreter cpu = run("""
				MOV	#2000,R2
				MOV	#177777,(R2)
				MOVB	#200,R0
				MOVB	#200,(R2)
			""");
		assertEquals(0177600, cpu.getRegister(0));
		assertEquals(0177600, m_mem[02000 / 2], "only the low byte of memory changed");
	}

	@Test
	void byteAutoIncrementStepsByOneExceptOnTheStackPointer() {
		Pdp11Interpreter cpu = run("""
				MOV	#2000,R1
				MOV	#3000,SP
				TSTB	(R1)+
				TSTB	(SP)+
			""");
		assertEquals(02001, cpu.getRegister(1));
		assertEquals(03002, cpu.getRegister(6));
	}

	@Test
	void aByteWrittenToAnOddAddressIsTheHighByte() {
		run("""
				MOV	#2000,R2
				CLR	(R2)
				MOVB	#123,1(R2)
			""");
		assertEquals(0123 << 8, m_mem[02000 / 2]);
	}

	@Test
	void addSetsCarryAndCmpComparesUnsigned() {
		Pdp11Interpreter cpu = run("""
				MOV	#177777,R0
				ADD	#2,R0
				BCC	1$
				MOV	#1,R3
			1$:	CMP	#1,#177777
				BHIS	2$
				MOV	#1,R4
			2$:
			""");
		assertEquals(1, cpu.getRegister(0));
		assertEquals(1, cpu.getRegister(3), "the add carried");
		assertEquals(1, cpu.getRegister(4), "1 is lower than 177777 unsigned");
	}

	@Test
	void aLoopWithDecAndBneRunsItsCount() {
		Pdp11Interpreter cpu = run("""
				CLR	R0
				MOV	#5,R1
			1$:	ADD	#3,R0
				DEC	R1
				BNE	1$
			""");
		assertEquals(15, cpu.getRegister(0));
	}

	@Test
	void jsrAndRtsCallThroughTheStack() {
		Pdp11Interpreter cpu = run("""
				MOV	#4000,SP
				JSR	PC,10$
				MOV	#7,R1
				BR	20$
			10$:	MOV	#6,R0
				RTS	PC
			20$:
			""");
		assertEquals(6, cpu.getRegister(0));
		assertEquals(7, cpu.getRegister(1));
		assertEquals(04000, cpu.getRegister(6));
	}

	@Test
	void shiftsAndSwab() {
		Pdp11Interpreter cpu = run("""
				MOV	#100001,R0
				ASR	R0
				MOV	#1,R1
				ASL	R1
				MOV	#1234,R2
				SWAB	R2
			""");
		assertEquals(0140000, cpu.getRegister(0));
		assertEquals(2, cpu.getRegister(1));
		assertEquals(0x9C02, cpu.getRegister(2));
	}

	/** What an 11/05 has not got is refused, not guessed at. */
	@Test
	void sobIsNotAnElevenOhFiveInstruction() {
		assertThrows(Pdp11Interpreter.Unsupported.class, () -> run("""
				MOV	#3,R0
			1$:	SOB	R0,1$
			"""));
	}

	@Test
	void aWordAtAnOddAddressIsABusError() {
		assertThrows(Pdp11Interpreter.BusError.class, () -> run("""
				MOV	#2001,R0
				TST	(R0)
			"""));
	}
}
