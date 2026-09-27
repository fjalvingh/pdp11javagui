package to.etc.pdp11.core.disas;

/**
 * The operand shape of an instruction - what follows the mnemonic and where the fields are.
 *
 * <p>Ported from {@code TOpClass} ({@code Pdp11DisasU.pas:71-103}), which mirrors SimH's
 * {@code I_V_xxx} classes in {@code pdp11_sys.c}. {@link Disassembler} switches on this.</p>
 */
enum OpClass {
	/** No operand: HALT, RTI, MOVC. */
	NPN,
	/** {@code op Rn}, register in bits 2..0: RTS, FADD, L2DR. */
	REG,
	/** {@code op dst}: CLR, TST, JMP, MFPI. */
	SOP,
	/** {@code op n}, a 3-bit literal in bits 2..0: SPL. */
	B3,
	/** {@code op n}, a 6-bit literal in bits 5..0: MARK. */
	B6,
	/** {@code op n}, an 8-bit literal in bits 7..0: EMT, TRAP. */
	B8,
	/** {@code op target}: the conditional branches. */
	BR,
	/** {@code op Rn,target}, always backwards: SOB. */
	SOB,
	/** {@code op src,dst}: MOV, CMP, ADD. */
	DOP,
	/** {@code op Rn,dst}: JSR, XOR. */
	RSOP,
	/** {@code op src,Rn}: MUL, DIV, ASH, ASHC. */
	SOPR,
	/** Condition-code clear group. Decodes exactly like {@link #NPN}. */
	CCC,
	/** Condition-code set group. Decodes exactly like {@link #NPN}. */
	CCS,
	/** {@code op fdst}: CLRF, TSTF, ABSF, NEGF. */
	FOP,
	/** {@code op ACn,fdst}: STF, STCFD. */
	AFOP,
	/** {@code op fsrc,ACn}: ADDF, MULF, LDF, SUBF, CMPF, DIVF, MODF, LDCFD. */
	FOPA,
	/** {@code op ACn,dst} with an integer destination: STEXP. */
	ASOP,
	/** {@code op ACn,dst} with an integer destination: the STCFI family. */
	ASMD,
	/** {@code op src,ACn} with an integer source: LDEXP. */
	SOPA,
	/** {@code op src,ACn} with an integer source: the LDCIF family. */
	SMDA
}
