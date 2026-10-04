package to.etc.pdp11.common.macro11.asm;

import java.util.EnumSet;
import java.util.Set;

/**
 * How to assemble.
 *
 * @param relocationBase   where the first relocatable section is placed. There is no linker, so
 *                         the relocatable sections are put one after the other from here, in the
 *                         order the source first opens them.
 * @param disabledWarnings warnings not to give
 */
public record AssemblerOptions(int relocationBase, Set<WarningKind> disabledWarnings) {
	/** Relocatable code from address 0, every warning on. */
	public static final AssemblerOptions DEFAULT = new AssemblerOptions(0, Set.of());

	public AssemblerOptions {
		if(relocationBase < 0 || relocationBase > 0xFFFF || (relocationBase & 1) != 0)
			throw new IllegalArgumentException("The relocation base must be an even 16-bit address, not "
				+ Integer.toOctalString(relocationBase));
		disabledWarnings = Set.copyOf(disabledWarnings);
	}

	public AssemblerOptions withRelocationBase(int base) {
		return new AssemblerOptions(base, disabledWarnings);
	}

	public AssemblerOptions withoutWarning(WarningKind kind) {
		Set<WarningKind> s = disabledWarnings.isEmpty() ? EnumSet.noneOf(WarningKind.class) : EnumSet.copyOf(disabledWarnings);
		s.add(kind);
		return new AssemblerOptions(relocationBase, s);
	}

	public AssemblerOptions withWarnings(Set<WarningKind> disabled) {
		return new AssemblerOptions(relocationBase, disabled);
	}
}
