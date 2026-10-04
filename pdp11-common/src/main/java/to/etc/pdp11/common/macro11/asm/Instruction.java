package to.etc.pdp11.common.macro11.asm;

import java.util.HashMap;
import java.util.Map;

/**
 * The PDP-11 instruction set as MACRO-11 knows it: the basic set, EIS, FIS and FPP.
 *
 * <p>Besides its opcode and operand format each instruction says three things the warnings need:
 * whether it works on bytes, whether it writes its destination, and whether it always transfers
 * control - after which an instruction with no label cannot be reached.</p>
 */
enum Instruction {
	//-- No operands
	HALT(0000000, Format.NONE),
	WAIT(0000001, Format.NONE),
	RTI(0000002, Format.NONE, Flag.TRANSFER),
	BPT(0000003, Format.NONE),
	IOT(0000004, Format.NONE),
	RESET(0000005, Format.NONE),
	RTT(0000006, Format.NONE, Flag.TRANSFER),
	MFPT(0000007, Format.NONE),
	RETURN(0000207, Format.NONE, Flag.TRANSFER),
	NOP(0000240, Format.NONE),
	CLC(0000241, Format.NONE),
	CLV(0000242, Format.NONE),
	CLZ(0000244, Format.NONE),
	CLN(0000250, Format.NONE),
	CCC(0000257, Format.NONE),
	SEC(0000261, Format.NONE),
	SEV(0000262, Format.NONE),
	SEZ(0000264, Format.NONE),
	SEN(0000270, Format.NONE),
	SCC(0000277, Format.NONE),
	MED6X(0076600, Format.NONE),
	MED74C(0076601, Format.NONE),
	XFC(0076700, Format.NONE),

	//-- One general operand
	JMP(0000100, Format.SINGLE, Flag.TRANSFER, Flag.JUMP),
	CALLR(0000100, Format.SINGLE, Flag.TRANSFER, Flag.JUMP),
	CALL(0004700, Format.SINGLE, Flag.JUMP),
	SWAB(0000300, Format.SINGLE, Flag.WRITES),
	CLR(0005000, Format.SINGLE, Flag.WRITES),
	CLRB(0105000, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	COM(0005100, Format.SINGLE, Flag.WRITES),
	COMB(0105100, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	INC(0005200, Format.SINGLE, Flag.WRITES),
	INCB(0105200, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	DEC(0005300, Format.SINGLE, Flag.WRITES),
	DECB(0105300, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	NEG(0005400, Format.SINGLE, Flag.WRITES),
	NEGB(0105400, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	ADC(0005500, Format.SINGLE, Flag.WRITES),
	ADCB(0105500, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	SBC(0005600, Format.SINGLE, Flag.WRITES),
	SBCB(0105600, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	TST(0005700, Format.SINGLE),
	TSTB(0105700, Format.SINGLE, Flag.BYTE),
	ROR(0006000, Format.SINGLE, Flag.WRITES),
	RORB(0106000, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	ROL(0006100, Format.SINGLE, Flag.WRITES),
	ROLB(0106100, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	ASR(0006200, Format.SINGLE, Flag.WRITES),
	ASRB(0106200, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	ASL(0006300, Format.SINGLE, Flag.WRITES),
	ASLB(0106300, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	MTPS(0106400, Format.SINGLE, Flag.BYTE),
	MFPI(0006500, Format.SINGLE),
	MFPD(0106500, Format.SINGLE),
	MTPI(0006600, Format.SINGLE, Flag.WRITES),
	MTPD(0106600, Format.SINGLE, Flag.WRITES),
	SXT(0006700, Format.SINGLE, Flag.WRITES),
	MFPS(0106700, Format.SINGLE, Flag.WRITES, Flag.BYTE),
	CSM(0007000, Format.SINGLE),
	TSTSET(0007200, Format.SINGLE, Flag.WRITES),
	WRTLCK(0007300, Format.SINGLE, Flag.WRITES),

	//-- Two general operands
	MOV(0010000, Format.DOUBLE, Flag.WRITES),
	MOVB(0110000, Format.DOUBLE, Flag.WRITES, Flag.BYTE),
	CMP(0020000, Format.DOUBLE),
	CMPB(0120000, Format.DOUBLE, Flag.BYTE),
	BIT(0030000, Format.DOUBLE),
	BITB(0130000, Format.DOUBLE, Flag.BYTE),
	BIC(0040000, Format.DOUBLE, Flag.WRITES),
	BICB(0140000, Format.DOUBLE, Flag.WRITES, Flag.BYTE),
	BIS(0050000, Format.DOUBLE, Flag.WRITES),
	BISB(0150000, Format.DOUBLE, Flag.WRITES, Flag.BYTE),
	ADD(0060000, Format.DOUBLE, Flag.WRITES),
	SUB(0160000, Format.DOUBLE, Flag.WRITES),

	//-- Branches
	BR(0000400, Format.BRANCH, Flag.TRANSFER),
	BNE(0001000, Format.BRANCH),
	BEQ(0001400, Format.BRANCH),
	BGE(0002000, Format.BRANCH),
	BLT(0002400, Format.BRANCH),
	BGT(0003000, Format.BRANCH),
	BLE(0003400, Format.BRANCH),
	BPL(0100000, Format.BRANCH),
	BMI(0100400, Format.BRANCH),
	BHI(0101000, Format.BRANCH),
	BLOS(0101400, Format.BRANCH),
	BVC(0102000, Format.BRANCH),
	BVS(0102400, Format.BRANCH),
	BCC(0103000, Format.BRANCH),
	BHIS(0103000, Format.BRANCH),
	BCS(0103400, Format.BRANCH),
	BLO(0103400, Format.BRANCH),

	//-- Register and operand
	JSR(0004000, Format.REGISTER_DESTINATION, Flag.JUMP),
	XOR(0074000, Format.REGISTER_DESTINATION, Flag.WRITES),
	RTS(0000200, Format.REGISTER, Flag.TRANSFER),
	SOB(0077000, Format.SOB),
	MUL(0070000, Format.SOURCE_REGISTER),
	DIV(0071000, Format.SOURCE_REGISTER, Flag.EVEN_REGISTER),
	ASH(0072000, Format.SOURCE_REGISTER),
	ASHC(0073000, Format.SOURCE_REGISTER, Flag.EVEN_REGISTER),

	//-- A number in the instruction word
	EMT(0104000, Format.TRAP),
	TRAP(0104400, Format.TRAP),
	MARK(0006400, Format.MARK),
	SPL(0000230, Format.SPL),

	//-- FIS: a register holding the address of the operands
	FADD(0075000, Format.REGISTER),
	FSUB(0075010, Format.REGISTER),
	FMUL(0075020, Format.REGISTER),
	FDIV(0075030, Format.REGISTER),

	//-- FPP
	CFCC(0170000, Format.NONE),
	SETF(0170001, Format.NONE),
	SETI(0170002, Format.NONE),
	STA0(0170005, Format.NONE),
	STB0(0170006, Format.NONE),
	SETD(0170011, Format.NONE),
	SETL(0170012, Format.NONE),
	LDFPS(0170100, Format.SINGLE),
	STFPS(0170200, Format.SINGLE, Flag.WRITES),
	STST(0170300, Format.SINGLE, Flag.WRITES),
	CLRF(0170400, Format.SINGLE, Flag.WRITES),
	CLRD(0170400, Format.SINGLE, Flag.WRITES),
	TSTF(0170500, Format.SINGLE),
	TSTD(0170500, Format.SINGLE),
	ABSF(0170600, Format.SINGLE, Flag.WRITES),
	ABSD(0170600, Format.SINGLE, Flag.WRITES),
	NEGF(0170700, Format.SINGLE, Flag.WRITES),
	NEGD(0170700, Format.SINGLE, Flag.WRITES),
	MULF(0171000, Format.FP_SOURCE),
	MULD(0171000, Format.FP_SOURCE),
	MODF(0171400, Format.FP_SOURCE),
	MODD(0171400, Format.FP_SOURCE),
	ADDF(0172000, Format.FP_SOURCE),
	ADDD(0172000, Format.FP_SOURCE),
	LDF(0172400, Format.FP_SOURCE),
	LDD(0172400, Format.FP_SOURCE),
	SUBF(0173000, Format.FP_SOURCE),
	SUBD(0173000, Format.FP_SOURCE),
	CMPF(0173400, Format.FP_SOURCE),
	CMPD(0173400, Format.FP_SOURCE),
	STF(0174000, Format.FP_DESTINATION, Flag.WRITES),
	STD(0174000, Format.FP_DESTINATION, Flag.WRITES),
	DIVF(0174400, Format.FP_SOURCE),
	DIVD(0174400, Format.FP_SOURCE),
	STEXP(0175000, Format.FP_DESTINATION, Flag.WRITES),
	STCFI(0175400, Format.FP_DESTINATION, Flag.WRITES),
	STCFL(0175400, Format.FP_DESTINATION, Flag.WRITES),
	STCDI(0175400, Format.FP_DESTINATION, Flag.WRITES),
	STCDL(0175400, Format.FP_DESTINATION, Flag.WRITES),
	STCFD(0176000, Format.FP_DESTINATION, Flag.WRITES),
	STCDF(0176000, Format.FP_DESTINATION, Flag.WRITES),
	LDEXP(0176400, Format.FP_SOURCE),
	LDCIF(0177000, Format.FP_SOURCE),
	LDCID(0177000, Format.FP_SOURCE),
	LDCLF(0177000, Format.FP_SOURCE),
	LDCLD(0177000, Format.FP_SOURCE),
	LDCFD(0177400, Format.FP_SOURCE),
	LDCDF(0177400, Format.FP_SOURCE);

	/** How an instruction's operands are written and encoded. */
	enum Format {
		/** No operands. */
		NONE,

		/** {@code OP dst}: six bits of operand. */
		SINGLE,

		/** {@code OP src,dst}. */
		DOUBLE,

		/** {@code OP label}: a signed word offset of eight bits. */
		BRANCH,

		/** {@code OP R,dst}: {@code JSR}, {@code XOR}. */
		REGISTER_DESTINATION,

		/** {@code OP src,R}: {@code MUL}, {@code DIV}, {@code ASH}, {@code ASHC}. */
		SOURCE_REGISTER,

		/** {@code OP R}: {@code RTS} and the FIS instructions. */
		REGISTER,

		/** {@code SOB R,label}: a backward word offset of six bits. */
		SOB,

		/** {@code EMT n}, {@code TRAP n}: eight bits, 0 when left out. */
		TRAP,

		/** {@code MARK n}: six bits. */
		MARK,

		/** {@code SPL n}: three bits. */
		SPL,

		/** {@code OP src,AC}: a floating-point accumulator 0-3 as destination. */
		FP_SOURCE,

		/** {@code OP AC,dst}: a floating-point accumulator 0-3 as source. */
		FP_DESTINATION
	}

	enum Flag {
		/** A byte instruction. */
		BYTE,

		/** Writes its destination operand. */
		WRITES,

		/** Always transfers control, so the next instruction is only reached through a label. */
		TRANSFER,

		/** Its operand is where to go, which must be an instruction - so a register is illegal. */
		JUMP,

		/** Needs an even register, the first of a pair. */
		EVEN_REGISTER
	}

	private static final Map<String, Instruction> BY_NAME = new HashMap<>();

	static {
		for(Instruction i : values())
			BY_NAME.put(i.name(), i);
	}

	private final int m_opcode;

	private final Format m_format;

	private final Flag[] m_flags;

	Instruction(int opcode, Format format, Flag... flags) {
		m_opcode = opcode;
		m_format = format;
		m_flags = flags;
	}

	/** The instruction with this mnemonic, or null. */
	static Instruction find(String mnemonic) {
		return BY_NAME.get(mnemonic);
	}

	int getOpcode() {
		return m_opcode;
	}

	Format getFormat() {
		return m_format;
	}

	boolean has(Flag flag) {
		for(Flag f : m_flags) {
			if(f == flag)
				return true;
		}
		return false;
	}

	boolean isByte() {
		return has(Flag.BYTE);
	}
}
