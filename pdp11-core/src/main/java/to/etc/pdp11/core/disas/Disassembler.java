package to.etc.pdp11.core.disas;

import to.etc.pdp11.core.util.Octal;

import java.util.ArrayList;
import java.util.List;

import static to.etc.pdp11.core.disas.OpClass.*;

/**
 * A PDP-11 disassembler.
 *
 * <p>Ported from {@code common/Pdp11DisasU.pas}, itself a fresh implementation written during
 * the Linux port to replace the Windows-only {@code PDP11DISAS.DLL} whose source was lost.
 * Covers the base ISA, EIS (MUL/DIV/ASH/ASHC/XOR/SOB), FP11 floating point and the Commercial
 * Instruction Set.</p>
 *
 * <h2>Two limitations, both inherent to static disassembly</h2>
 *
 * <p><b>FP11 precision is not in the instruction.</b> The F/D (single/double) and I/L
 * (integer/long) variants share identical opcode bits; which one an instruction is depends on
 * the live FPS mode register, which a memory image does not contain. We print the F/I
 * mnemonic, the FP11 power-on default. The D/L table entries are kept, with bit 16 or 17 set
 * in their opcode value so they can never match a plain 16-bit word - a caller with a real FPS
 * value could enable them by OR-ing {@link #FPS_L} or {@link #FPS_D} into the word.</p>
 *
 * <p><b>CIS inline instructions carry their operands as following data words.</b> MOVCI,
 * ADDNI and friends embed packed descriptors after the opcode. We print the mnemonic and
 * consume one word, which is what SimH's own disassembler does; the descriptor words then
 * appear as their own nonsensical lines. Non-inline CIS instructions are unaffected - their
 * operands are in fixed registers by architectural convention.</p>
 *
 * <h2>Where this deliberately differs from SimH</h2>
 *
 * <p>SimH is the reference this was checked against ({@code pdp11_sys.c}:
 * {@code opcode[]}/{@code opc_val[]}/{@code masks[]}), and two known disagreements survive:</p>
 *
 * <ul>
 *   <li><b>Opcodes {@code 000256} and {@code 000276}.</b> SimH's {@code opcode[]} table has
 *       the same copy-paste duplicate in each half of the condition-code group: {@code 000256}
 *       repeats {@code 000255}'s {@code CLN CLZ CLC} and {@code 000276} repeats
 *       {@code 000275}'s {@code SEN SEZ SEC}. Bit 0 is clear in both, so C is not affected and
 *       V is: the decodes are {@code CLN CLZ CLV} and {@code SEN SEZ SEV}. We follow the
 *       documented per-flag bit encoding. (The Pascal unit header records only the first of
 *       the pair; the second surfaced when the cross-check corpus went exhaustive.)</li>
 * </ul>
 *
 * <h2>Where this deliberately differs from the Pascal</h2>
 *
 * <p>Diffing both implementations over all 65536 words - see {@code tools/pascal-disas-diff.sh} -
 * turns up 183 differing words and no others, in two groups. Both are bugs in the Pascal,
 * found by the SimH cross-check of PLAN.md phase 1 and confirmed by running it:</p>
 *
 * <ul>
 *   <li><b>SPL ({@code 0002 3N}).</b> The level is bits 2..0. The Pascal reads bits 8..6
 *       instead ({@code Pdp11DisasU.pas:513}, reusing the {@code reg3} field meant for the
 *       RSOP/SOPR classes). Those bits are part of SPL's own opcode and are always
 *       {@code 010}, so it prints {@code SPL 2} for every SPL there is - only {@code 000232}
 *       comes out right, by coincidence.</li>
 *   <li><b>Mode 0 of a float operand.</b> The register field is three bits and the FP11 has
 *       six accumulators, but {@code FacName} masks it to two ({@code :342}, called from
 *       {@code :410}), so {@code CLRF AC4} and {@code CLRF AC5} print as {@code AC0} and
 *       {@code AC1}. {@code macro11} assembles {@code CLRF AC5} to {@code 0170405}, which
 *       settles it. 176 words are affected.</li>
 * </ul>
 *
 * <p>Everything else agrees exactly, word counts included.</p>
 */
public final class Disassembler {
	/** OR into an instruction word to select the FP11 long-integer variants. */
	public static final int FPS_L = 0x10000;

	/** OR into an instruction word to select the FP11 double-precision variants. */
	public static final int FPS_D = 0x20000;

	private static final String[] REG_NAMES = {"R0", "R1", "R2", "R3", "R4", "R5", "SP", "PC"};

	/**
	 * One entry of the opcode table: an instruction matches when
	 * {@code (word & mask) == opVal}. Entries are tried in order, so more specific ones come
	 * first.
	 *
	 * @param opVal may have bit 16 or 17 set for the FP11 D/L variants, which is what keeps
	 *              them from ever matching a 16-bit word - see the class comment
	 */
	private record OpEntry(String name, int opVal, int mask, OpClass cls) {
	}

	private static OpEntry e(String name, int opVal, int mask, OpClass cls) {
		return new OpEntry(name, opVal, mask, cls);
	}

	/**
	 * The instruction table, 217 entries, transcribed mechanically from
	 * {@code Pdp11DisasU.pas:111-329} - which was itself pulled programmatically from SimH
	 * rather than typed in by eye. Order is significant.
	 */
	private static final OpEntry[] OP_TABLE = {
		e("HALT", 0x00000, 0x0ffff, NPN),
		e("WAIT", 0x00001, 0x0ffff, NPN),
		e("RTI", 0x00002, 0x0ffff, NPN),
		e("BPT", 0x00003, 0x0ffff, NPN),
		e("IOT", 0x00004, 0x0ffff, NPN),
		e("RESET", 0x00005, 0x0ffff, NPN),
		e("RTT", 0x00006, 0x0ffff, NPN),
		e("MFPT", 0x00007, 0x0ffff, NPN),
		e("JMP", 0x00040, 0x0ffc0, SOP),
		e("RTS", 0x00080, 0x0fff8, REG),
		e("SPL", 0x00098, 0x0fff8, B3),
		e("NOP", 0x000a0, 0x0ffff, CCC),
		e("CLC", 0x000a1, 0x0ffff, CCC),
		e("CLV", 0x000a2, 0x0ffff, CCC),
		e("CLV CLC", 0x000a3, 0x0ffff, NPN),
		e("CLZ", 0x000a4, 0x0ffff, CCC),
		e("CLZ CLC", 0x000a5, 0x0ffff, NPN),
		e("CLZ CLV", 0x000a6, 0x0ffff, NPN),
		e("CLZ CLV CLC", 0x000a7, 0x0ffff, NPN),
		e("CLN", 0x000a8, 0x0ffff, CCC),
		e("CLN CLC", 0x000a9, 0x0ffff, NPN),
		e("CLN CLV", 0x000aa, 0x0ffff, NPN),
		e("CLN CLV CLC", 0x000ab, 0x0ffff, NPN),
		e("CLN CLZ", 0x000ac, 0x0ffff, NPN),
		e("CLN CLZ CLC", 0x000ad, 0x0ffff, NPN),
		e("CLN CLZ CLV", 0x000ae, 0x0ffff, NPN),
		e("CCC", 0x000af, 0x0ffff, CCC),
		e("NOP", 0x000b0, 0x0ffff, CCS),
		e("SEC", 0x000b1, 0x0ffff, CCS),
		e("SEV", 0x000b2, 0x0ffff, CCS),
		e("SEV SEC", 0x000b3, 0x0ffff, NPN),
		e("SEZ", 0x000b4, 0x0ffff, CCS),
		e("SEZ SEC", 0x000b5, 0x0ffff, NPN),
		e("SEZ SEV", 0x000b6, 0x0ffff, NPN),
		e("SEZ SEV SEC", 0x000b7, 0x0ffff, NPN),
		e("SEN", 0x000b8, 0x0ffff, CCS),
		e("SEN SEC", 0x000b9, 0x0ffff, NPN),
		e("SEN SEV", 0x000ba, 0x0ffff, NPN),
		e("SEN SEV SEC", 0x000bb, 0x0ffff, NPN),
		e("SEN SEZ", 0x000bc, 0x0ffff, NPN),
		e("SEN SEZ SEC", 0x000bd, 0x0ffff, NPN),
		e("SEN SEZ SEV", 0x000be, 0x0ffff, NPN),
		e("SCC", 0x000bf, 0x0ffff, CCS),
		e("SWAB", 0x000c0, 0x0ffc0, SOP),
		e("BR", 0x00100, 0x0ff00, BR),
		e("BNE", 0x00200, 0x0ff00, BR),
		e("BEQ", 0x00300, 0x0ff00, BR),
		e("BGE", 0x00400, 0x0ff00, BR),
		e("BLT", 0x00500, 0x0ff00, BR),
		e("BGT", 0x00600, 0x0ff00, BR),
		e("BLE", 0x00700, 0x0ff00, BR),
		e("JSR", 0x00800, 0x0fe00, RSOP),
		e("CLR", 0x00a00, 0x0ffc0, SOP),
		e("COM", 0x00a40, 0x0ffc0, SOP),
		e("INC", 0x00a80, 0x0ffc0, SOP),
		e("DEC", 0x00ac0, 0x0ffc0, SOP),
		e("NEG", 0x00b00, 0x0ffc0, SOP),
		e("ADC", 0x00b40, 0x0ffc0, SOP),
		e("SBC", 0x00b80, 0x0ffc0, SOP),
		e("TST", 0x00bc0, 0x0ffc0, SOP),
		e("ROR", 0x00c00, 0x0ffc0, SOP),
		e("ROL", 0x00c40, 0x0ffc0, SOP),
		e("ASR", 0x00c80, 0x0ffc0, SOP),
		e("ASL", 0x00cc0, 0x0ffc0, SOP),
		e("MARK", 0x00d00, 0x0ffc0, B6),
		e("MFPI", 0x00d40, 0x0ffc0, SOP),
		e("MTPI", 0x00d80, 0x0ffc0, SOP),
		e("SXT", 0x00dc0, 0x0ffc0, SOP),
		e("CSM", 0x00e00, 0x0ffc0, SOP),
		e("TSTSET", 0x00e80, 0x0ffc0, SOP),
		e("WRTLCK", 0x00ec0, 0x0ffc0, SOP),
		e("MOV", 0x01000, 0x0f000, DOP),
		e("CMP", 0x02000, 0x0f000, DOP),
		e("BIT", 0x03000, 0x0f000, DOP),
		e("BIC", 0x04000, 0x0f000, DOP),
		e("BIS", 0x05000, 0x0f000, DOP),
		e("ADD", 0x06000, 0x0f000, DOP),
		e("MUL", 0x07000, 0x0fe00, SOPR),
		e("DIV", 0x07200, 0x0fe00, SOPR),
		e("ASH", 0x07400, 0x0fe00, SOPR),
		e("ASHC", 0x07600, 0x0fe00, SOPR),
		e("XOR", 0x07800, 0x0fe00, RSOP),
		e("FADD", 0x07a00, 0x0fff8, REG),
		e("FSUB", 0x07a08, 0x0fff8, REG),
		e("FMUL", 0x07a10, 0x0fff8, REG),
		e("FDIV", 0x07a18, 0x0fff8, REG),
		e("L2DR", 0x07c10, 0x0fff8, REG),
		e("MOVC", 0x07c18, 0x0ffff, NPN),
		e("MOVRC", 0x07c19, 0x0ffff, NPN),
		e("MOVTC", 0x07c1a, 0x0ffff, NPN),
		e("LOCC", 0x07c20, 0x0ffff, NPN),
		e("SKPC", 0x07c21, 0x0ffff, NPN),
		e("SCANC", 0x07c22, 0x0ffff, NPN),
		e("SPANC", 0x07c23, 0x0ffff, NPN),
		e("CMPC", 0x07c24, 0x0ffff, NPN),
		e("MATC", 0x07c25, 0x0ffff, NPN),
		e("ADDN", 0x07c28, 0x0ffff, NPN),
		e("SUBN", 0x07c29, 0x0ffff, NPN),
		e("CMPN", 0x07c2a, 0x0ffff, NPN),
		e("CVTNL", 0x07c2b, 0x0ffff, NPN),
		e("CVTPN", 0x07c2c, 0x0ffff, NPN),
		e("CVTNP", 0x07c2d, 0x0ffff, NPN),
		e("ASHN", 0x07c2e, 0x0ffff, NPN),
		e("CVTLN", 0x07c2f, 0x0ffff, NPN),
		e("L3DR", 0x07c30, 0x0fff8, REG),
		e("ADDP", 0x07c38, 0x0ffff, NPN),
		e("SUBP", 0x07c39, 0x0ffff, NPN),
		e("CMPP", 0x07c3a, 0x0ffff, NPN),
		e("CVTPL", 0x07c3b, 0x0ffff, NPN),
		e("MULP", 0x07c3c, 0x0ffff, NPN),
		e("DIVP", 0x07c3d, 0x0ffff, NPN),
		e("ASHP", 0x07c3e, 0x0ffff, NPN),
		e("CVTLP", 0x07c3f, 0x0ffff, NPN),
		e("MOVCI", 0x07c58, 0x0ffff, NPN),
		e("MOVRCI", 0x07c59, 0x0ffff, NPN),
		e("MOVTCI", 0x07c5a, 0x0ffff, NPN),
		e("LOCCI", 0x07c60, 0x0ffff, NPN),
		e("SKPCI", 0x07c61, 0x0ffff, NPN),
		e("SCANCI", 0x07c62, 0x0ffff, NPN),
		e("SPANCI", 0x07c63, 0x0ffff, NPN),
		e("CMPCI", 0x07c64, 0x0ffff, NPN),
		e("MATCI", 0x07c65, 0x0ffff, NPN),
		e("ADDNI", 0x07c68, 0x0ffff, NPN),
		e("SUBNI", 0x07c69, 0x0ffff, NPN),
		e("CMPNI", 0x07c6a, 0x0ffff, NPN),
		e("CVTNLI", 0x07c6b, 0x0ffff, NPN),
		e("CVTPNI", 0x07c6c, 0x0ffff, NPN),
		e("CVTNPI", 0x07c6d, 0x0ffff, NPN),
		e("ASHNI", 0x07c6e, 0x0ffff, NPN),
		e("CVTLNI", 0x07c6f, 0x0ffff, NPN),
		e("ADDPI", 0x07c78, 0x0ffff, NPN),
		e("SUBPI", 0x07c79, 0x0ffff, NPN),
		e("CMPPI", 0x07c7a, 0x0ffff, NPN),
		e("CVTPLI", 0x07c7b, 0x0ffff, NPN),
		e("MULPI", 0x07c7c, 0x0ffff, NPN),
		e("DIVPI", 0x07c7d, 0x0ffff, NPN),
		e("ASHPI", 0x07c7e, 0x0ffff, NPN),
		e("CVTLPI", 0x07c7f, 0x0ffff, NPN),
		e("SOB", 0x07e00, 0x0fe00, SOB),
		e("BPL", 0x08000, 0x0ff00, BR),
		e("BMI", 0x08100, 0x0ff00, BR),
		e("BHI", 0x08200, 0x0ff00, BR),
		e("BLOS", 0x08300, 0x0ff00, BR),
		e("BVC", 0x08400, 0x0ff00, BR),
		e("BVS", 0x08500, 0x0ff00, BR),
		e("BCC", 0x08600, 0x0ff00, BR),
		e("BCS", 0x08700, 0x0ff00, BR),
		e("EMT", 0x08800, 0x0ff00, B8),
		e("TRAP", 0x08900, 0x0ff00, B8),
		e("CLRB", 0x08a00, 0x0ffc0, SOP),
		e("COMB", 0x08a40, 0x0ffc0, SOP),
		e("INCB", 0x08a80, 0x0ffc0, SOP),
		e("DECB", 0x08ac0, 0x0ffc0, SOP),
		e("NEGB", 0x08b00, 0x0ffc0, SOP),
		e("ADCB", 0x08b40, 0x0ffc0, SOP),
		e("SBCB", 0x08b80, 0x0ffc0, SOP),
		e("TSTB", 0x08bc0, 0x0ffc0, SOP),
		e("RORB", 0x08c00, 0x0ffc0, SOP),
		e("ROLB", 0x08c40, 0x0ffc0, SOP),
		e("ASRB", 0x08c80, 0x0ffc0, SOP),
		e("ASLB", 0x08cc0, 0x0ffc0, SOP),
		e("MTPS", 0x08d00, 0x0ffc0, SOP),
		e("MFPD", 0x08d40, 0x0ffc0, SOP),
		e("MTPD", 0x08d80, 0x0ffc0, SOP),
		e("MFPS", 0x08dc0, 0x0ffc0, SOP),
		e("MOVB", 0x09000, 0x0f000, DOP),
		e("CMPB", 0x0a000, 0x0f000, DOP),
		e("BITB", 0x0b000, 0x0f000, DOP),
		e("BICB", 0x0c000, 0x0f000, DOP),
		e("BISB", 0x0d000, 0x0f000, DOP),
		e("SUB", 0x0e000, 0x0f000, DOP),
		e("CFCC", 0x0f000, 0x0ffff, NPN),
		e("SETF", 0x0f001, 0x0ffff, NPN),
		e("SETI", 0x0f002, 0x0ffff, NPN),
		e("SETD", 0x0f009, 0x0ffff, NPN),
		e("SETL", 0x0f00a, 0x0ffff, NPN),
		e("LDFPS", 0x0f040, 0x0ffc0, SOP),
		e("STFPS", 0x0f080, 0x0ffc0, SOP),
		e("STST", 0x0f0c0, 0x0ffc0, SOP),
		e("CLRF", 0x0f100, 0x2ffc0, FOP),
		e("CLRD", 0x2f100, 0x2ffc0, FOP),
		e("TSTF", 0x0f140, 0x2ffc0, FOP),
		e("TSTD", 0x2f140, 0x2ffc0, FOP),
		e("ABSF", 0x0f180, 0x2ffc0, FOP),
		e("ABSD", 0x2f180, 0x2ffc0, FOP),
		e("NEGF", 0x0f1c0, 0x2ffc0, FOP),
		e("NEGD", 0x2f1c0, 0x2ffc0, FOP),
		e("MULF", 0x0f200, 0x2ff00, FOPA),
		e("MULD", 0x2f200, 0x2ff00, FOPA),
		e("MODF", 0x0f300, 0x2ff00, FOPA),
		e("MODD", 0x2f300, 0x2ff00, FOPA),
		e("ADDF", 0x0f400, 0x2ff00, FOPA),
		e("ADDD", 0x2f400, 0x2ff00, FOPA),
		e("LDF", 0x0f500, 0x2ff00, FOPA),
		e("LDD", 0x2f500, 0x2ff00, FOPA),
		e("SUBF", 0x0f600, 0x2ff00, FOPA),
		e("SUBD", 0x2f600, 0x2ff00, FOPA),
		e("CMPF", 0x0f700, 0x2ff00, FOPA),
		e("CMPD", 0x2f700, 0x2ff00, FOPA),
		e("STF", 0x0f800, 0x2ff00, AFOP),
		e("STD", 0x2f800, 0x2ff00, AFOP),
		e("DIVF", 0x0f900, 0x2ff00, FOPA),
		e("DIVD", 0x2f900, 0x2ff00, FOPA),
		e("STEXP", 0x0fa00, 0x0ff00, ASOP),
		e("STCFI", 0x0fb00, 0x3ff00, ASMD),
		e("STCDI", 0x2fb00, 0x3ff00, ASMD),
		e("STCFL", 0x1fb00, 0x3ff00, ASMD),
		e("STCDL", 0x3fb00, 0x3ff00, ASMD),
		e("STCFD", 0x0fc00, 0x2ff00, AFOP),
		e("STCDF", 0x2fc00, 0x2ff00, AFOP),
		e("LDEXP", 0x0fd00, 0x0ff00, SOPA),
		e("LDCIF", 0x0fe00, 0x3ff00, SMDA),
		e("LDCID", 0x2fe00, 0x3ff00, SMDA),
		e("LDCLF", 0x1fe00, 0x3ff00, SMDA),
		e("LDCLD", 0x3fe00, 0x3ff00, SMDA),
		e("LDCFD", 0x0ff00, 0x2ff00, FOPA),
		e("LDCDF", 0x2ff00, 0x2ff00, FOPA)
	};

	private Disassembler() {
	}

	/**
	 * Disassemble the instruction at {@code addr}.
	 *
	 * <p>Never fails: an unrecognized opcode, or one whose extension word is not valid memory,
	 * comes back as a one-word {@code .WORD nnnnnn} with {@link DecodedInstruction#recognized()}
	 * false. That matters because the Disassembler window walks memory read back from a real
	 * machine, where both happen constantly.</p>
	 */
	public static DecodedInstruction disassemble(MemoryImage mem, int addr) {
		addr &= 0xFFFF;
		int w = mem.readWord(addr);

		OpEntry entry = null;
		for(OpEntry oe : OP_TABLE) {
			if((w & oe.mask()) == oe.opVal()) {
				entry = oe;
				break;
			}
		}
		if(entry == null)
			return word(addr, w);

		int srcSpec = (w >>> 6) & 0x3F;             // "SS" field, bits 11..6
		int dstSpec = w & 0x3F;                     // "DD" field, bits 5..0
		int reg3 = srcSpec & 7;                     // 3-bit register, bits 8..6
		int fac2 = (w >>> 6) & 3;                   // 2-bit FP11 accumulator number
		int lit8 = w & 0xFF;

		Cursor cur = new Cursor((addr + 2) & 0xFFFF);
		String operands;

		switch(entry.cls()) {
			case NPN, CCC, CCS -> operands = "";

			case REG -> operands = regName(dstSpec);

			//-- SPL's level is bits 2..0. The Pascal reads bits 8..6; see the class comment.
			case B3 -> operands = octPlain(w & 7);

			case B6 -> operands = octPlain(dstSpec);

			case B8 -> operands = octPlain(lit8);

			case BR -> {
				int offset = (byte) lit8;           // the branch displacement is signed
				operands = octW(addr + 2 + 2 * offset);
			}

			//-- SOB only ever branches backwards, so the 6-bit displacement is subtracted.
			case SOB -> operands = regName(reg3) + "," + octW(addr + 2 - 2 * dstSpec);

			case SOP -> operands = decodeOperand(mem, dstSpec, true, cur);

			case FOP -> operands = decodeOperand(mem, dstSpec, false, cur);

			case DOP -> {
				String s1 = decodeOperand(mem, srcSpec, true, cur);
				String s2 = decodeOperand(mem, dstSpec, true, cur);
				operands = s1 + "," + s2;
			}

			case RSOP -> operands = regName(reg3) + "," + decodeOperand(mem, dstSpec, true, cur);

			case SOPR -> operands = decodeOperand(mem, dstSpec, true, cur) + "," + regName(reg3);

			case AFOP -> operands = facName(fac2) + "," + decodeOperand(mem, dstSpec, false, cur);

			case FOPA -> operands = decodeOperand(mem, dstSpec, false, cur) + "," + facName(fac2);

			case ASOP, ASMD -> operands = facName(fac2) + "," + decodeOperand(mem, dstSpec, true, cur);

			case SOPA, SMDA -> operands = decodeOperand(mem, dstSpec, true, cur) + "," + facName(fac2);

			default -> throw new IllegalStateException("Unhandled operand class " + entry.cls());
		}

		if(!cur.ok)
			return word(addr, w);

		//-- Word count from how far the cursor moved, wrapping at 64 KB like the machine does.
		int consumed = ((cur.pos - addr) & 0xFFFF) / 2;
		return new DecodedInstruction(addr, consumed, entry.name(), operands, true);
	}

	/**
	 * Disassemble a whole image, skipping addresses whose memory is not valid. Ported from
	 * {@code Disas11} ({@code Pdp11DisasU.pas:588-626}), minus its fixed output buffer - that
	 * signature existed only to match the retired DLL's ABI.
	 */
	public static List<DecodedInstruction> disassembleAll(MemoryImage mem) {
		List<DecodedInstruction> list = new ArrayList<>();
		int addr = 0;
		while(addr <= MemoryImage.SIZE - 2) {
			if(mem.isWordValid(addr)) {
				DecodedInstruction di = disassemble(mem, addr);
				list.add(di);
				addr += di.words() * 2;
			} else {
				addr += 2;
			}
		}
		return list;
	}

	/**
	 * The listing format {@code Disas11} produces:
	 * {@code "AAAAAA: WWWWWW WWWWWW WWWWWW  mnemonic operands"}, one line per instruction, up
	 * to three raw words blank-padded. Kept byte-identical to the Pascal so the two can be
	 * diffed over a corpus.
	 */
	public static String listing(MemoryImage mem) {
		StringBuilder sb = new StringBuilder();
		for(DecodedInstruction di : disassembleAll(mem)) {
			sb.append(di.addressOctal()).append(": ");
			for(int i = 0; i < 3; i++) {
				if(i < di.words())
					sb.append(octW(mem.readWord(di.address() + i * 2))).append(' ');
				else
					sb.append("       ");
			}
			sb.append(' ').append(di.text()).append('\n');
		}
		return sb.toString();
	}

	/** The {@code .WORD} fallback, one word consumed. */
	private static DecodedInstruction word(int addr, int w) {
		return new DecodedInstruction(addr, 1, ".WORD", octW(w), false);
	}

	/** Tracks the next unconsumed word, and whether decoding is still viable. */
	private static final class Cursor {
		private int pos;

		private boolean ok = true;

		private Cursor(int pos) {
			this.pos = pos;
		}
	}

	/**
	 * Decode one 6-bit addressing-mode specifier: 3 bits of mode, 3 of register. Advances the
	 * cursor past any extension word the mode consumes.
	 *
	 * <p>{@code isInteger} decides whether mode 0 (register direct) names a general register -
	 * the normal case - or an FP11 accumulator, which float operands do because in this one
	 * mode the CPU addresses its own AC file.</p>
	 *
	 * <p>When a needed extension word is not valid memory the cursor is marked not-ok and the
	 * caller falls back to {@code .WORD}.</p>
	 */
	private static String decodeOperand(MemoryImage mem, int spec, boolean isInteger, Cursor cur) {
		if(!cur.ok)
			return "";

		int reg = spec & 7;
		int mode = (spec >>> 3) & 7;

		switch(mode) {
			case 0:
				//-- Mode 0 of a float operand names an FP11 accumulator, and the field is the
				//-- full 3 bits: AC0..AC5 exist. The Pascal masks it to 2 bits
				//-- (FacName(reg) at Pdp11DisasU.pas:410), so it prints AC1 for AC5. macro11
				//-- assembles "CLRF AC5" to 170405, which settles it.
				return isInteger ? regName(reg) : accumulatorName(reg);

			case 1:
				return "(" + regName(reg) + ")";

			case 2:
				if(reg != 7)
					return "(" + regName(reg) + ")+";
				//-- (PC)+ is immediate: the operand is the word that follows.
				return "#" + octW(fetch(mem, cur));

			case 3:
				if(reg != 7)
					return "@(" + regName(reg) + ")+";
				//-- @(PC)+ is absolute: the following word is the address.
				return "@#" + octW(fetch(mem, cur));

			case 4:
				return "-(" + regName(reg) + ")";

			case 5:
				return "@-(" + regName(reg) + ")";

			case 6: {
				int nval = fetch(mem, cur);
				if(!cur.ok)
					return "";
				if(reg != 7)
					return octW(nval) + "(" + regName(reg) + ")";
				//-- Index off PC is relative: the cursor has already advanced past the
				//-- extension word, so it holds the PC value the machine will add to.
				return octW(nval + cur.pos);
			}

			case 7: {
				int nval = fetch(mem, cur);
				if(!cur.ok)
					return "";
				if(reg != 7)
					return "@" + octW(nval) + "(" + regName(reg) + ")";
				return "@" + octW(nval + cur.pos);
			}

			default:
				throw new IllegalStateException("Addressing mode " + mode + " cannot occur");
		}
	}

	/** Read the extension word at the cursor and step past it, or mark the cursor not-ok. */
	private static int fetch(MemoryImage mem, Cursor cur) {
		if(!mem.isWordValid(cur.pos)) {
			cur.ok = false;
			return 0;
		}
		int v = mem.readWord(cur.pos);
		cur.pos = (cur.pos + 2) & 0xFFFF;
		return v;
	}

	private static String regName(int r) {
		return REG_NAMES[r & 7];
	}

	/**
	 * FP11 accumulator name for the dedicated AC field of a double-operand float instruction.
	 * That field really is two bits wide, so only AC0..AC3 can be named there.
	 */
	private static String facName(int f) {
		return "AC" + (f & 3);
	}

	/**
	 * FP11 accumulator name for mode 0 of a float operand, where the register field is three
	 * bits. The FP11 has six accumulators, so 6 and 7 name nothing; they are marked
	 * {@code ?6}/{@code ?7}, the same way SimH marks them, rather than silently printing a
	 * register that does not exist.
	 */
	private static String accumulatorName(int f) {
		int n = f & 7;
		return n <= 5 ? "AC" + n : "?" + n;
	}

	/** Six-digit octal: addresses, branch targets and literal words. */
	private static String octW(int v) {
		return Octal.format(v & 0xFFFF, 6);
	}

	/** Minimal-width octal: the 3-, 6- and 8-bit literal instruction fields. */
	private static String octPlain(int v) {
		return Octal.format(v & 0xFFFF, 1);
	}
}
