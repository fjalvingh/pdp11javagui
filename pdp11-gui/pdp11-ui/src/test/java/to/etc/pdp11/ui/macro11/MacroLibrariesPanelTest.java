package to.etc.pdp11.ui.macro11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The macro library list, and an assembly that uses what is in it.
 */
class MacroLibrariesPanelTest {
	/**
	 * The smallest RT-11 macro library holding one macro: a header block, a directory block, and
	 * the text from block 2. See {@code MacroLibrary} for the format.
	 */
	private static Path library(Path dir, String file, String macro, String text) throws Exception {
		byte[] b = new byte[3 * 512];
		put(b, 0, 001001);
		put(b, 2, 000500);
		put(b, 032, 8);
		put(b, 034, 1);
		put(b, 036, 1);
		String name = (macro + "      ").substring(0, 6);
		put(b, 512, rad50(name.substring(0, 3)));
		put(b, 514, rad50(name.substring(3)));
		put(b, 516, 2);
		put(b, 518, 0);
		byte[] t = text.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(t, 0, b, 1024, t.length);
		Path p = dir.resolve(file);
		Files.write(p, b);
		return p;
	}

	private static int rad50(String three) {
		String set = " ABCDEFGHIJKLMNOPQRSTUVWXYZ$.?0123456789";
		int w = 0;
		for(char c : three.toCharArray())
			w = w * 050 + set.indexOf(c);
		return w;
	}

	private static void put(byte[] b, int at, int value) {
		b[at] = (byte) value;
		b[at + 1] = (byte) (value >> 8);
	}

	@Test
	void aLibraryIsCheckedWhenItIsAdded(@TempDir Path dir) throws Exception {
		Path good = library(dir, "GOOD.MLB", "PUT", ".MACRO PUT\n\t.WORD 7\n.ENDM\n");
		Path bad = dir.resolve("BAD.MLB");
		Files.write(bad, new byte[1024]);

		MacroLibrariesPanel panel = Edt.call(() -> new MacroLibrariesPanel(List.of()));
		assertTrue(Edt.call(() -> panel.addLibrary(good)));
		assertTrue(Edt.call(() -> panel.getStatus().getText()).contains("1 macros"));

		assertFalse(Edt.call(() -> panel.addLibrary(bad)));
		assertTrue(Edt.call(() -> panel.getStatus().getText()).contains("not a macro library"));
		assertFalse(Edt.call(() -> panel.addLibrary(good)), "a library is only listed once");
		assertEquals(List.of(good), Edt.call(panel::getLibraries));
	}

	@Test
	void theOrderCanBeChanged(@TempDir Path dir) throws Exception {
		Path a = library(dir, "A.MLB", "PUT", ".MACRO PUT\n.ENDM\n");
		Path b = library(dir, "B.MLB", "PUT", ".MACRO PUT\n.ENDM\n");
		MacroLibrariesPanel panel = Edt.call(() -> new MacroLibrariesPanel(List.of(a, b)));
		Edt.run(() -> {
			panel.getList().setSelectedIndex(1);
			panel.getComponents();
		});
		Edt.run(() -> ((javax.swing.JButton) findButton(panel, "Up")).doClick());
		assertEquals(List.of(b, a), Edt.call(panel::getLibraries));
		Edt.run(() -> ((javax.swing.JButton) findButton(panel, "Remove")).doClick());
		assertEquals(List.of(a), Edt.call(panel::getLibraries));
	}

	private static java.awt.Component findButton(java.awt.Container c, String text) {
		for(java.awt.Component x : c.getComponents()) {
			if(x instanceof javax.swing.JButton b && b.getText().equals(text))
				return b;
			if(x instanceof java.awt.Container inner) {
				java.awt.Component found = findButton(inner, text);
				if(found != null)
					return found;
			}
		}
		return null;
	}

	@Test
	void thePanelRenders(@TempDir Path dir) throws Exception {
		Path good = library(dir, "GOOD.MLB", "PUT", ".MACRO PUT\n.ENDM\n");
		MacroLibrariesPanel panel = Edt.call(() -> new MacroLibrariesPanel(List.of(good)));
		Path file = UiRenderer.renderToFile(panel, 560, 260, Path.of("target", "ui-render", "macro-libraries.png"));
		assertTrue(Files.size(file) > 0);
	}

	@Test
	void anAssemblySearchesTheLibrariesOfTheModel(@TempDir Path dir) throws Exception {
		Path lib = library(dir, "MY.MLB", "PUT", ".MACRO PUT X\n\t.WORD X\n.ENDM\n");
		Path src = dir.resolve("p.mac");
		Files.writeString(src, "\t.asect\n\t.=1000\n\t.mcall\tPUT\n\tPUT\t123\n\t.end\n", StandardCharsets.ISO_8859_1);

		AppContext ctx = TestContext.create(dir);
		CompletableFuture<AssemblerModel.Outcome> done = new CompletableFuture<>();
		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
			ctx.getAssembler().setMacroLibraries(List.of(lib));
			ctx.getAssembler().assemble(done::complete);
		});
		AssemblerModel.Outcome outcome = done.get(30, TimeUnit.SECONDS);
		assertTrue(outcome.ok(), outcome.message());
		assertEquals(0123, Edt.call(() -> ctx.getAssembler().getGroup().cell(0).getEditValue().word()));
		assertEquals(List.of(lib.toString()), ctx.getSettings().getMacroLibraries(), "and it is remembered");
	}
}
