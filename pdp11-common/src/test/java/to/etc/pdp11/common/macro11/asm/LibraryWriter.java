package to.etc.pdp11.common.macro11.asm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes macro libraries in both formats, for testing the reader by round trip.
 *
 * <p>Laid out as the real ones are: a header block, a directory block, then the modules. See
 * {@link MacroLibrary} for the formats.</p>
 */
final class LibraryWriter {
	private static final int BLOCK = 512;

	/** Directory entries: one block's worth. */
	private static final int ENTRIES = BLOCK / 8;

	private final Map<String, String> m_modules = new LinkedHashMap<>();

	/** A module: the macro's name, and the text that defines it, lines ending in a newline. */
	LibraryWriter add(String name, String text) {
		m_modules.put(name, text);
		return this;
	}

	byte[] rt11() {
		byte[] out = new byte[2 * BLOCK];
		header(out, 000500, 1);
		ByteArrayOutputStream modules = new ByteArrayOutputStream();
		int i = 0;
		for(Map.Entry<String, String> e : m_modules.entrySet()) {
			int position = out.length + modules.size();
			entry(out, i++, e.getKey(), position / BLOCK, position % BLOCK);
			modules.writeBytes(e.getValue().replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
		}
		return blocks(out, modules.toByteArray());
	}

	byte[] rsx() {
		byte[] out = new byte[2 * BLOCK];
		header(out, Rad50.encodeWord("V05").orElseThrow(), 2);
		ByteArrayOutputStream modules = new ByteArrayOutputStream();
		int i = 0;
		for(Map.Entry<String, String> e : m_modules.entrySet()) {
			int position = out.length + modules.size();
			entry(out, i++, e.getKey(), position / BLOCK + 1, position % BLOCK);
			ByteArrayOutputStream records = new ByteArrayOutputStream();
			for(String line : e.getValue().split("\n")) {
				byte[] b = line.getBytes(StandardCharsets.ISO_8859_1);
				word(records, b.length);
				records.writeBytes(b);
				if((b.length & 1) != 0)
					records.write(0);
			}
			int size = 16 + records.size();
			word(modules, 16);
			word(modules, 0);
			word(modules, size >> 16);
			word(modules, size & 0xFFFF);
			for(int k = 0; k < 4; k++)
				word(modules, 0);
			modules.writeBytes(records.toByteArray());
		}
		return blocks(out, modules.toByteArray());
	}

	/** A library copied as text: a newline after every block. */
	static byte[] copiedAsText(byte[] library) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for(int i = 0; i < library.length; i += BLOCK) {
			out.write(library, i, BLOCK);
			out.write('\n');
		}
		return out.toByteArray();
	}

	private static void header(byte[] out, int version, int directoryBlock) {
		put(out, 0, 001001);
		put(out, 2, version);
		put(out, 032, 8);
		put(out, 034, directoryBlock);
		put(out, 036, ENTRIES);
		for(int i = 0; i < ENTRIES; i++) {
			for(int k = 0; k < 8; k++)
				out[BLOCK + i * 8 + k] = (byte) 0377;
		}
	}

	private static void entry(byte[] out, int index, String name, int block, int offset) {
		String padded = (name + "      ").substring(0, 6);
		int at = BLOCK + index * 8;
		put(out, at, Rad50.encodeWord(padded.substring(0, 3)).orElseThrow());
		put(out, at + 2, Rad50.encodeWord(padded.substring(3)).orElseThrow());
		put(out, at + 4, block);
		put(out, at + 6, offset);
	}

	/** The parts, padded with NULs to a whole number of blocks. */
	private static byte[] blocks(byte[] head, byte[] modules) {
		int length = head.length + modules.length;
		byte[] all = new byte[(length + BLOCK - 1) / BLOCK * BLOCK];
		System.arraycopy(head, 0, all, 0, head.length);
		System.arraycopy(modules, 0, all, head.length, modules.length);
		return all;
	}

	private static void put(byte[] b, int at, int value) {
		b[at] = (byte) value;
		b[at + 1] = (byte) (value >> 8);
	}

	private static void word(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}
}
