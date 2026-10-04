package to.etc.pdp11.common.macro11.asm;

import java.util.HashMap;
import java.util.Map;

/**
 * The MACRO-11 directives this assembler knows.
 *
 * <p>The {@code .IFxx} forms are {@code .IF xx} written as one word; they carry the condition
 * they stand for.</p>
 */
enum Directive {
	ASCII(".ASCII"),
	ASCIZ(".ASCIZ"),
	ASECT(".ASECT"),
	BLKB(".BLKB"),
	BLKW(".BLKW"),
	BYTE(".BYTE"),
	CROSS(".CROSS"),
	CSECT(".CSECT"),
	DSABL(".DSABL"),
	ENABL(".ENABL"),
	END(".END"),
	ENDC(".ENDC"),
	ENDM(".ENDM"),
	ENDR(".ENDR"),
	EOT(".EOT"),
	ERROR(".ERROR"),
	EVEN(".EVEN"),
	FLT2(".FLT2"),
	FLT4(".FLT4"),
	GLOBL(".GLOBL"),
	IDENT(".IDENT"),
	IF(".IF"),
	IFF(".IFF"),
	IFT(".IFT"),
	IFTF(".IFTF"),
	IIF(".IIF"),
	IFDF(".IFDF", "DF"),
	IFNDF(".IFNDF", "NDF"),
	IFEQ(".IFEQ", "EQ"),
	IFNE(".IFNE", "NE"),
	IFGT(".IFGT", "GT"),
	IFGE(".IFGE", "GE"),
	IFLT(".IFLT", "LT"),
	IFLE(".IFLE", "LE"),
	IFZ(".IFZ", "Z"),
	IFNZ(".IFNZ", "NZ"),
	IFG(".IFG", "G"),
	IFL(".IFL", "L"),
	IFB(".IFB", "B"),
	IFNB(".IFNB", "NB"),
	IFIDN(".IFIDN", "IDN"),
	IFDIF(".IFDIF", "DIF"),
	INCLUDE(".INCLUDE"),
	IRP(".IRP"),
	IRPC(".IRPC"),
	LIBRARY(".LIBRARY"),
	LIMIT(".LIMIT"),
	LIST(".LIST"),
	MACRO(".MACRO"),
	MACR(".MACR"),
	MCALL(".MCALL"),
	MDELETE(".MDELETE"),
	MEXIT(".MEXIT"),
	NARG(".NARG"),
	NCHR(".NCHR"),
	NLIST(".NLIST"),
	NOCROSS(".NOCROSS"),
	NTYPE(".NTYPE"),
	ODD(".ODD"),
	PACKED(".PACKED"),
	PAGE(".PAGE"),
	PRINT(".PRINT"),
	PSECT(".PSECT"),
	RAD50(".RAD50"),
	RADIX(".RADIX"),
	REM(".REM"),
	REPT(".REPT"),
	RESTORE(".RESTORE"),
	SAVE(".SAVE"),
	SBTTL(".SBTTL"),
	TITLE(".TITLE"),
	WEAK(".WEAK"),
	WORD(".WORD");

	private static final Map<String, Directive> BY_NAME = new HashMap<>();

	static {
		for(Directive d : values())
			BY_NAME.put(d.m_name, d);
	}

	private final String m_name;

	private final String m_condition;

	Directive(String name) {
		this(name, null);
	}

	Directive(String name, String condition) {
		m_name = name;
		m_condition = condition;
	}

	/**
	 * The directive with this name, or null.
	 *
	 * <p>DEC's assembler only looks at the first six characters of a name, so {@code .ENABLE} is
	 * {@code .ENABL} and {@code .INCLU} is {@code .INCLUDE}; sources use both.</p>
	 */
	static Directive find(String name) {
		Directive d = BY_NAME.get(name);
		if(d != null || name.length() < 6)
			return d;
		String six = name.substring(0, 6);
		for(Directive x : values()) {
			if(x.m_name.length() >= 6 && x.m_name.substring(0, 6).equals(six))
				return x;
		}
		return null;
	}

	String getName() {
		return m_name;
	}

	/** For {@code .IFxx}: the condition, such as {@code EQ}; otherwise null. */
	String getCondition() {
		return m_condition;
	}

	/** {@code .IF}, {@code .IFxx} or {@code .IIF}: something that opens a conditional or tests one. */
	boolean isConditional() {
		return this == IF || this == IIF || m_condition != null;
	}

	/** A directive that is only about a conditional block already open. */
	boolean isSubconditional() {
		return this == IFF || this == IFT || this == IFTF;
	}

	/** {@code .MACRO} or its abbreviation. */
	boolean isMacroDefinition() {
		return this == MACRO || this == MACR;
	}

	/** Something that opens a body ended by {@code .ENDR}. */
	boolean isRepeat() {
		return this == REPT || this == IRP || this == IRPC;
	}
}
