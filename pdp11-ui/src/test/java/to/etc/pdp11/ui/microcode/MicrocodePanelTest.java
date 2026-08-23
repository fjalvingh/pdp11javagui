package to.etc.pdp11.ui.microcode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.microcode.MicrocodeField;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.JTable;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The microcode window, with no display and no machine.
 *
 * <p>{@code Pdp1144MicrocodeTest} covers what the listing says; this covers what the window does
 * with it - that it opens on the packaged listing rather than on "code not loaded", that the
 * three ways of searching find the same microword, that Next follows the fall-through and Back
 * comes home, and that a search for something that is not there leaves what is on screen
 * alone.</p>
 */
class MicrocodePanelTest {
	private static final int WIDTH = 900;

	private static final int HEIGHT = 700;

	/** Rows before the fields start: the symbolic tag and the address. */
	private static final int FIELDS_START = 2;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static MicrocodePanel panel(Path dir) {
		AppContext ctx = TestContext.create(dir);
		MicrocodePanel panel = Edt.call(() -> new MicrocodePanel(ctx));
		Edt.run(panel::attach);
		return panel;
	}

	private static String cell(JTable table, int row, int column) {
		return Edt.call(() -> String.valueOf(table.getValueAt(row, column)));
	}

	/**
	 * The window opens on the microcode, not on an invitation to go and find a 1981 DEC document
	 * - which is what the Pascal opens on, because the listing is not shipped with it.
	 */
	@Test
	void itOpensOnThePackagedListing(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);

		assertNotNull(panel.getMicrocode());
		assertEquals(1018, panel.getMicrocode().size());
		assertTrue(panel.getMicrocode().isOk(), "and it verifies");
		assertEquals(0, panel.getCurrent().getAddress(), "starting at the first microword");
		assertTrue(Edt.call(panel::getStatusText).contains("µPC = 0000"), Edt.call(panel::getStatusText));
	}

	/** Every field on one screen: two rows of heading, 37 fields, and four of provenance. */
	@Test
	void oneMicrowordIsOneTableOfEveryField(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> panel.searchFor("0461"));

		assertEquals(FIELDS_START + MicrocodeField.ALL.size() + 4, Edt.call(panel.getTable()::getRowCount));
		assertEquals("Symbolic tag", cell(panel.getTable(), 0, 0));
		assertEquals("2-I", cell(panel.getTable(), 0, 2));
		assertEquals("Address", cell(panel.getTable(), 1, 0));
		assertEquals("0461", cell(panel.getTable(), 1, 2));
		assertEquals("NEXT MICROWORD ADDRESS", cell(panel.getTable(), FIELDS_START, 0));
		assertEquals("102:93", cell(panel.getTable(), FIELDS_START, 1));
	}

	/**
	 * A value that is named is shown named. Reading {@code 2} against {@code UNIBUS CONTROL} and
	 * having to remember that it means DATO is the thing this window exists to stop.
	 */
	@Test
	void aFieldShowsItsNumberAndWhatItMeans(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> panel.searchFor("0732"));

		assertEquals("2 = DATO", infoOf(panel, "UNIBUS CONTROL"));
		assertEquals("3 = UBUS", infoOf(panel, "AMUX CONTROL"));
		//-- An FP11 field: the print set names none of their values, so a number is all there is.
		assertEquals("3", infoOf(panel, "BSEL"));
	}

	/**
	 * The fields that are not at rest are the microword. Highlighting is how three rows out of
	 * thirty-nine become the answer to "what does this one do".
	 */
	@Test
	void onlyTheFieldsThisMicrowordSetsAreHighlighted(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> panel.searchFor("0461"));

		List<String> highlighted = Edt.call(() -> {
			List<String> l = new java.util.ArrayList<>();
			for(int i = 0; i < panel.getModel().getRowCount(); i++) {
				MicrocodeTableModel.Row r = panel.getModel().getRow(i);
				if(r.highlight())
					l.add(r.label());
			}
			return l;
		});
		assertEquals(List.of("ALU/BLEG CONTROL", "SCRATCH PAD DST SELECT", "ROM SCRATCH PAD ADDRESS",
			"Source code"), highlighted, "R0 := R0+1, and the source line that says so");
	}

	@Test
	void theThreeWaysOfSearchingFindTheSameMicroword(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);

		Edt.run(() -> panel.searchFor("0732"));
		assertEquals("2-J", panel.getCurrent().getSymbolicTag());
		int listingLine = panel.getCurrent().getLineNumber();

		Edt.run(() -> panel.getSearchBySelector().setSelectedItem(MicrocodePanel.SearchBy.TAG));
		Edt.run(() -> panel.searchFor("2-J"));
		assertEquals(0732, panel.getCurrent().getAddress());

		Edt.run(() -> panel.getSearchBySelector().setSelectedItem(MicrocodePanel.SearchBy.LINE));
		Edt.run(() -> panel.searchFor(String.valueOf(listingLine)));
		assertEquals(0732, panel.getCurrent().getAddress());
	}

	/** Dropping the box open is the listing's index, so it holds every microword. */
	@Test
	void theSearchBoxIsAnIndexOfTheWholeListing(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		assertEquals(1018, Edt.call(() -> panel.getSearchBox().getItemCount()));
		assertEquals("0000", Edt.call(() -> panel.getSearchBox().getItemAt(0)));

		Edt.run(() -> panel.getSearchBySelector().setSelectedItem(MicrocodePanel.SearchBy.TAG));
		assertEquals(1018, Edt.call(() -> panel.getSearchBox().getItemCount()));
		//-- Tag order is flow order, so the first entry is the first block of page 1.
		assertEquals("1-A", Edt.call(() -> panel.getSearchBox().getItemAt(0)));
	}

	@Test
	void nextFollowsTheFallThroughAndBackComesHome(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		assertFalse(Edt.call(() -> panel.getBackButton().isEnabled()), "nowhere to go back to yet");

		//-- A search is a move too, so it can be undone like one.
		Edt.run(() -> panel.searchFor("0732"));
		assertTrue(Edt.call(() -> panel.getBackButton().isEnabled()));

		Edt.run(panel::next);
		assertEquals(043, panel.getCurrent().getAddress(), "2-J falls through to 2-L");
		assertEquals("2-L", panel.getCurrent().getSymbolicTag());

		Edt.run(panel::back);
		assertEquals(0732, panel.getCurrent().getAddress());
		Edt.run(panel::back);
		assertEquals(0, panel.getCurrent().getAddress(), "back to where the window opened");
		assertFalse(Edt.call(() -> panel.getBackButton().isEnabled()), "and the history is spent");
	}

	/**
	 * What the fall-through <i>into</i> a microword is - which the Pascal has no way of asking,
	 * and which is how microcode is read when you are working out how the machine got somewhere.
	 */
	@Test
	void aMicrowordSaysWhatFallsThroughToIt(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> panel.searchFor("0732"));
		Edt.run(panel::next);

		String from = rowInfo(panel, "Jumped to from");
		assertTrue(from.contains("2-J (0732)"), from);
	}

	/**
	 * A search that finds nothing says so and changes nothing. The Pascal reads an address it
	 * cannot parse as zero and jumps to the first microword instead, which looks like the
	 * window ignoring what was typed.
	 */
	@Test
	void aSearchThatFindsNothingLeavesTheScreenAlone(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> panel.searchFor("0732"));
		var before = panel.getCurrent();

		Edt.run(() -> panel.searchFor("nonsense"));
		assertSame(before, panel.getCurrent());
		assertTrue(Edt.call(panel::getStatusText).contains("No microword"), Edt.call(panel::getStatusText));
	}

	/** A listing of one's own, and it is remembered for next time. */
	@Test
	void anotherListingCanBeLoadedAndIsRemembered(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MicrocodePanel panel = Edt.call(() -> new MicrocodePanel(ctx));
		Edt.run(panel::attach);

		Path own = dir.resolve("mine.txt");
		Files.write(own, List.of(
			"U 0000, 0010,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-B",
			"U 0010, 0000,6345,0300,0146,1740,3033,4000,0000,017     ;1042   010:    1-B:    J/1-A"));
		Edt.run(() -> panel.loadFrom(own));

		assertEquals(2, panel.getMicrocode().size());
		assertEquals("mine.txt", panel.getMicrocode().getSourceName());
		assertEquals(own.toAbsolutePath().toString(), ctx.getSettings().getLastMicrocodeFile());
		//-- And a window opened again with that setting comes back to it rather than to the
		//-- packaged listing.
		MicrocodePanel second = Edt.call(() -> new MicrocodePanel(ctx));
		Edt.run(second::attach);
		assertEquals(2, second.getMicrocode().size());
	}

	/** A remembered file that has gone away is not a reason to open an empty window. */
	@Test
	void aRememberedListingThatHasGoneFallsBackToThePackagedOne(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		ctx.getSettings().setLastMicrocodeFile(dir.resolve("not-there.txt").toString());
		MicrocodePanel panel = Edt.call(() -> new MicrocodePanel(ctx));
		Edt.run(panel::attach);

		assertEquals(1018, panel.getMicrocode().size());
	}

	@Test
	void theTableGetsTheRoomAndTheControlsOneRow(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> UiRenderer.layOut(panel, WIDTH, HEIGHT));

		Rectangle table = Edt.call(() -> panel.getTable().getParent().getParent().getBounds());
		assertTrue(table.height > HEIGHT / 2, "the table gets the room: " + table);
		assertTrue(table.y > 0 && table.y < 80, "the controls are one row: " + table);
		assertTrue(table.x + table.width <= WIDTH, "and it stays inside the panel");
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> panel.searchFor("0732"));
		Path file = Edt.call(() -> UiRenderer.renderToFile(panel, WIDTH, HEIGHT,
			Path.of("target", "ui-render", "microcode-panel.png")));
		assertTrue(Files.size(file) > 0);
	}

	private static String infoOf(MicrocodePanel panel, String fieldName) {
		return rowInfo(panel, fieldName);
	}

	private static String rowInfo(MicrocodePanel panel, String label) {
		return Edt.call(() -> {
			for(int i = 0; i < panel.getModel().getRowCount(); i++) {
				MicrocodeTableModel.Row r = panel.getModel().getRow(i);
				if(r.label().equals(label))
					return r.info();
			}
			throw new IllegalArgumentException("No row labelled " + label);
		});
	}
}
