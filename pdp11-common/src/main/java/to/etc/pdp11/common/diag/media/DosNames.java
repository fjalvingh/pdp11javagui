package to.etc.pdp11.common.diag.media;

import java.time.LocalDate;
import java.time.Year;

/**
 * The two encodings DOS-11 directories use, and XXDP's after them: RADIX-50 names and day-of-year
 * dates.
 *
 * <p>Decoding only. Nothing here writes a medium.</p>
 */
public final class DosNames {
	/**
	 * Code 29 is {@code %} in the DOS-11 and XXDP character set. MACRO-11 calls the same code
	 * {@code ?} or leaves it unused, which is why this is not the assembler's table.
	 */
	private static final String CHARACTERS = " ABCDEFGHIJKLMNOPQRSTUVWXYZ$.%0123456789";

	private DosNames() {
	}

	/**
	 * Three characters per word, padding included.
	 *
	 * <p>A word above {@code 0174777} is not RADIX-50 at all; its first character comes out as
	 * {@code '\0'} so that {@link #isPlausibleName} can refuse it rather than wrapping it into
	 * something that looks like a letter.</p>
	 */
	public static String decode(int... words) {
		StringBuilder sb = new StringBuilder(words.length * 3);
		for(int word : words) {
			int w = word & 0xffff;
			if(w >= 40 * 40 * 40) {
				sb.append("\0\0\0");
				continue;
			}
			sb.append(CHARACTERS.charAt(w / 1600));
			sb.append(CHARACTERS.charAt(w / 40 % 40));
			sb.append(CHARACTERS.charAt(w % 40));
		}
		return sb.toString();
	}

	/**
	 * {@code NAME.EXT} from the three words of a directory entry, padding removed.
	 */
	public static String fileName(int name1, int name2, int ext) {
		return decode(name1, name2).trim() + "." + decode(ext).trim();
	}

	/**
	 * Whether a decoded name is one a directory could actually hold: letters and digits, padded
	 * on the right only.
	 *
	 * <p>This is what tells a directory from a block of program code read as one. A block of code
	 * decodes to names too, but to names with embedded blanks, dollars and dots, or none at all.</p>
	 */
	public static boolean isPlausibleName(String nameDotExt) {
		int dot = nameDotExt.indexOf('.');
		if(dot <= 0)
			return false;
		return isPlausiblePart(nameDotExt.substring(0, dot), 6) && isPlausiblePart(nameDotExt.substring(dot + 1), 3);
	}

	private static boolean isPlausiblePart(String part, int max) {
		if(part.length() > max)
			return false;
		for(int i = 0; i < part.length(); i++) {
			char c = part.charAt(i);
			if(!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9'))
				return false;
		}
		return true;
	}

	/**
	 * A DOS-11 date: {@code (year - 1970) * 1000 + day of year}, in the low 15 bits.
	 *
	 * @return the date, or {@code null} for zero or for a day the year does not have
	 */
	public static LocalDate date(int word) {
		int value = word & 077777;
		if(value == 0)
			return null;
		int year = value / 1000 + 1970;
		int day = value % 1000;
		if(day < 1 || day > Year.of(year).length())
			return null;
		return LocalDate.ofYearDay(year, day);
	}
}
