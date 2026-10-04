package to.etc.pdp11.common.macro11.asm;

import java.util.Objects;

/**
 * Where something is in the source: a file or expansion name, a 1-based line and a 1-based
 * column.
 *
 * <p>Text that a macro call or a repeat block produced has no line in any file. Its location
 * names the expansion and the line within it, and {@link #expandedFrom()} says where the
 * expansion was asked for - which is itself a location, possibly inside another expansion. The
 * outermost one, {@link #root()}, is the line the user wrote and the one an editor can mark.</p>
 *
 * @param source       the file name, or a description of the expansion
 * @param line         1-based line within {@code source}
 * @param column       1-based column, or 0 when only the line is known
 * @param expandedFrom where the expansion holding this line was called from, or null in a file
 */
public record Location(String source, int line, int column, Location expandedFrom) {
	public Location {
		Objects.requireNonNull(source, "source");
	}

	/** A location in a file rather than in an expansion. */
	public static Location inFile(String source, int line, int column) {
		return new Location(source, line, column, null);
	}

	/** The same place, at another column. */
	public Location atColumn(int newColumn) {
		return new Location(source, line, newColumn, expandedFrom);
	}

	/** The same line with no column, which is what identifies a statement. */
	public Location lineOnly() {
		return column == 0 ? this : new Location(source, line, 0, expandedFrom);
	}

	/** Whether this line was produced by a macro or repeat expansion. */
	public boolean isExpansion() {
		return expandedFrom != null;
	}

	/** The location in the outermost file: where the user wrote the line that caused this. */
	public Location root() {
		Location l = this;
		while(l.expandedFrom != null)
			l = l.expandedFrom;
		return l;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(source).append(':').append(line);
		if(column > 0)
			sb.append(':').append(column);
		if(expandedFrom != null)
			sb.append(" (expanded at ").append(expandedFrom).append(')');
		return sb.toString();
	}
}
