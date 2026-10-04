package to.etc.pdp11.ui.diag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.diag.DiagnosticFamily;
import to.etc.pdp11.common.diag.DiagnosticLibrary;
import to.etc.pdp11.common.diag.DiagnosticSource;
import to.etc.pdp11.common.diag.HttpFetcher;
import to.etc.pdp11.common.diag.media.MediaFile;
import to.etc.pdp11.common.diag.media.MediaVolume;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.JTable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Diagnostics window without a network: a library already on disk, and a web held in memory.
 *
 * <p>What is tested here is the part only the window can get wrong - that it reads the library it
 * was given, that the buttons follow the jobs, that a collection ends up in the list. What the
 * list says and how it is filtered is {@code LibraryListingTest}'s; how media are read and
 * collected is the common module's.</p>
 */
class DiagnosticsPanelTest {
	private static final long TIMEOUT_MS = 30_000;

	/** The one source the collect test leaves ticked: a page of single programs. */
	private static final String AK6DN = "https://ak6dn.github.io/PDP-11/DIAGNOSTICS/";

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static void until(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while(System.currentTimeMillis() < deadline) {
			if(Edt.call(condition::getAsBoolean))
				return;
			try {
				Thread.sleep(20);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(x);
			}
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	/** A library with three programs on two media, one of them damaged. */
	private static void library(AppContext ctx) throws IOException {
		DiagnosticLibrary lib = DiagnosticLibrary.open(ctx.getLibraryDir().resolve(DiagnosticsPanel.DIRECTORY));
		LocalDate d = LocalDate.of(1989, 3, 1);
		lib.add("bitsavers-rl02", "https://www.bitsavers.org/x/xxdp25.rl02.gz", new MediaVolume("xxdp25.rl02",
			"RL02 disk, XXDP+ file system", new byte[512], List.of(
			new MediaFile("ZRLGE0.BIC", d, new byte[]{1, 2, 3}),
			new MediaFile("FKAAC0.BIC", d, new byte[]{4, 5, 6}),
			new MediaFile("XXDPXM.SYS", d, new byte[]{7, 8, 9})), List.of()), LocalDate.of(2026, 10, 4));
		lib.add("ak6dn-tu58", "https://ak6dn.github.io/x/1134_1.DSK", new MediaVolume("1134_1.DSK",
			"TU58 tape, DOS-11 file system", new byte[512], List.of(
			new MediaFile("FKAAC0.BIC", d, new byte[]{4, 5, 6}),
			new MediaFile("FKTGC0.BIC", d, new byte[]{1}, true)), List.of()), LocalDate.of(2026, 10, 4));
		lib.save();
	}

	private static DiagnosticsPanel panel(AppContext ctx, HttpFetcher web) {
		DiagnosticsPanel panel = Edt.call(() -> new DiagnosticsPanel(ctx, web));
		until("the library to be read", panel::isLoaded);
		return panel;
	}

	private static final HttpFetcher NO_NETWORK = uri -> {
		throw new IOException(uri + ": no network in a test");
	};

	@Test
	void theLibraryOnDiskIsListed(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		library(ctx);
		DiagnosticsPanel panel = panel(ctx, NO_NETWORK);
		JTable table = panel.getLibraryTab().getTable();
		assertEquals(4, Edt.call(table::getRowCount));
		assertEquals("XXDPXM.SYS", Edt.call(() -> table.getValueAt(0, 0)), "XXDP itself comes first");
		assertEquals("4 programs", Edt.call(() -> panel.getLibraryTab().getCount().getText()));
		assertEquals("damaged", Edt.call(() -> table.getModel().getValueAt(2, LibraryTab.ProgramModel.COL_STATUS)));
	}

	@Test
	void typingNarrowsTheListAndAFamilyNarrowsItFurther(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		library(ctx);
		DiagnosticsPanel panel = panel(ctx, NO_NETWORK);
		LibraryTab tab = panel.getLibraryTab();
		Edt.run(() -> tab.getFilter().setText("11/34"));
		assertEquals(2, Edt.call(() -> tab.getTable().getRowCount()));
		assertEquals("2 of 4 programs", Edt.call(() -> tab.getCount().getText()));
		Edt.run(() -> {
			tab.getFilter().setText("");
			for(int i = 0; i < tab.getFamily().getItemCount(); i++) {
				if(tab.getFamily().getItemAt(i).family() == DiagnosticFamily.PERIPHERALS)
					tab.getFamily().setSelectedIndex(i);
			}
		});
		assertEquals("ZRLGE0.BIC", Edt.call(() -> tab.getTable().getValueAt(0, 0)));
		assertEquals(1, Edt.call(() -> tab.getTable().getRowCount()));
	}

	@Test
	void selectingAProgramSaysWhereItCameFrom(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		library(ctx);
		DiagnosticsPanel panel = panel(ctx, NO_NETWORK);
		LibraryTab tab = panel.getLibraryTab();
		Edt.run(() -> tab.getFilter().setText("fkaac0"));
		Edt.run(() -> tab.getTable().setRowSelectionInterval(0, 0));
		String details = Edt.call(() -> tab.getDetails().getText());
		assertTrue(details.startsWith("FKAAC0.BIC - 11/34 BSC INST TST"), details);
		assertTrue(details.contains("xxdp25.rl02 (RL02 disk, XXDP+ file system)"), details);
		assertTrue(details.contains("1134_1.DSK (TU58 tape"), details);
		assertTrue(details.contains("DEC: CFKAAC0"), details);
	}

	@Test
	void findThenCollectPutsTheProgramsInTheLibrary(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		List<String> asked = new ArrayList<>();
		HttpFetcher web = uri -> {
			String u = uri.toString();
			synchronized(asked) {
				asked.add(u);
			}
			byte[] body;
			if(u.equals(AK6DN))
				body = "<a href=\"dl11.bin\">dl11.bin</a> <a href=\"dl11.ini\">dl11.ini</a> <a href=\"../\">up</a>".getBytes(StandardCharsets.ISO_8859_1);
			else if(u.equals(AK6DN + "dl11.bin"))
				body = new byte[]{1, 0, 6, 0, 0, 1, (byte) 0370};
			else
				throw new IOException(u + ": HTTP 404");
			return new HttpFetcher.Response(new ByteArrayInputStream(body), body.length);
		};
		DiagnosticsPanel panel = panel(ctx, web);
		CollectTab tab = panel.getCollectTab();
		//-- Untick everything but AK6DN's page, the way a person would.
		Edt.run(() -> {
			JTable sources = tab.getSourceTable();
			for(int i = 0; i < sources.getRowCount(); i++) {
				boolean keep = String.valueOf(sources.getValueAt(i, 2)).equals("ak6dn.github.io/PDP-11/DIAGNOSTICS/");
				sources.setValueAt(keep, i, 0);
			}
		});
		assertFalse(ctx.getSettings().isDiagnosticSourceChosen("bitsavers-rl02", true), "the choice is remembered");
		assertTrue(Edt.call(() -> tab.chosenSources().stream().map(DiagnosticSource::id).toList()).equals(List.of("ak6dn-diagnostics")));

		Edt.run(() -> tab.m_find.doClick());
		until("the search to finish", () -> !panel.isBusy());
		assertEquals(1, Edt.call(() -> tab.getFoundTable().getRowCount()), "dl11.ini is not a program, and ../ is not inside");
		assertEquals("new", Edt.call(() -> tab.getFoundTable().getValueAt(0, 2)));
		assertEquals("Collect 1 new", Edt.call(tab.m_collect::getText));
		assertTrue(Edt.call(tab.m_collect::isEnabled));

		Edt.run(() -> tab.m_collect.doClick());
		until("the collection to finish", () -> !panel.isBusy());
		assertTrue(Edt.call(() -> tab.getStatus().getText()).startsWith("Collected 1 of 1 downloads: 1 new programs"),
			Edt.call(() -> tab.getStatus().getText()));
		assertEquals("in the library", Edt.call(() -> tab.getFoundTable().getValueAt(0, 2)));
		assertFalse(Edt.call(tab.m_collect::isEnabled), "nothing left to collect");
		assertEquals("DL11.BIN", Edt.call(() -> panel.getLibraryTab().getTable().getValueAt(0, 0)));
		assertTrue(Files.exists(panel.getLibraryRoot().resolve("files/DL11.BIN")));
	}

	@Test
	void aSearchWithNothingTickedSaysSoInsteadOfSearching(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		DiagnosticsPanel panel = panel(ctx, NO_NETWORK);
		CollectTab tab = panel.getCollectTab();
		Edt.run(() -> {
			for(int i = 0; i < tab.getSourceTable().getRowCount(); i++)
				tab.getSourceTable().setValueAt(false, i, 0);
			tab.m_find.doClick();
		});
		assertEquals("Tick at least one source.", Edt.call(() -> tab.getStatus().getText()));
		assertFalse(Edt.call(panel::isBusy));
	}

	@Test
	void theTitleColumnKeepsItsWidthAtTheSmallestWindow(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		library(ctx);
		DiagnosticsPanel panel = panel(ctx, NO_NETWORK);
		UiRenderer.layOut(panel, 620, 420);
		int title = Edt.call(() -> panel.getLibraryTab().getTable().getColumnModel().getColumn(LibraryTab.ProgramModel.COL_TITLE).getWidth());
		assertTrue(title >= LibraryTab.ProgramModel.MIN_WIDTHS[LibraryTab.ProgramModel.COL_TITLE], "title column " + title);
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		library(ctx);
		DiagnosticsPanel panel = panel(ctx, NO_NETWORK);
		Path library = UiRenderer.renderToFile(panel, 900, 640, Path.of("target", "ui-render", "diagnostics-library.png"));
		Edt.run(() -> panel.getTabs().setSelectedIndex(1));
		Path collect = UiRenderer.renderToFile(panel, 900, 640, Path.of("target", "ui-render", "diagnostics-collect.png"));
		assertTrue(Files.size(library) > 0);
		assertTrue(Files.size(collect) > 0);
	}
}
