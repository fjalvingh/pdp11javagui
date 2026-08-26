package to.etc.pdp11.ui.microcode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.microcode.Kd11bFields;
import to.etc.pdp11.core.microcode.Pdp1144Fields;
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

	/** Rows before the fields start: the symbolic tag, the address and the decoded successor. */
	private static final int FIELDS_START = 3;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	/**
	 * A window showing the 11/44, which is what most of this file is about.
	 *
	 * <p>It has to be asked for. The window opens on the PDP-11/05 by default - that is the
	 * machine this application is being written beside - so a test about the 11/44's listing says
	 * so rather than relying on which entry happens to come first.</p>
	 */
	private static MicrocodePanel panel(Path dir) {
		return panel(dir, MicrocodeSource.PDP1144);
	}

	private static MicrocodePanel panel(Path dir, MicrocodeSource source) {
		AppContext ctx = TestContext.create(dir);
		ctx.getSettings().setMicrocodeSelection(source.getLabel());
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

		assertEquals(FIELDS_START + Pdp1144Fields.ARCHITECTURE.size() + 4, Edt.call(panel.getTable()::getRowCount));
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

		assertEquals("0043", infoOf(panel, "Next microword"));
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
		ctx.getSettings().setMicrocodeSelection(MicrocodeSource.PDP1144.getLabel());
		MicrocodePanel panel = Edt.call(() -> new MicrocodePanel(ctx));
		Edt.run(panel::attach);

		Path own = dir.resolve("mine.txt");
		Files.write(own, List.of(
			"U 0000, 0010,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-B",
			"U 0010, 0000,6345,0300,0146,1740,3033,4000,0000,017     ;1042   010:    1-B:    J/1-A"));
		Edt.run(() -> panel.loadFrom(own));

		assertEquals(2, panel.getMicrocode().size());
		assertEquals("mine.txt", panel.getMicrocode().getSourceName());
		assertEquals(own.toAbsolutePath().toString(),
			ctx.getSettings().getMicrocodeListing(MicrocodeSource.PDP1144.getLabel()));
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
		ctx.getSettings().setMicrocodeSelection(MicrocodeSource.PDP1144.getLabel());
		ctx.getSettings().setMicrocodeListing(MicrocodeSource.PDP1144.getLabel(),
			dir.resolve("not-there.txt").toString());
		MicrocodePanel panel = Edt.call(() -> new MicrocodePanel(ctx));
		Edt.run(panel::attach);

		assertEquals(1018, panel.getMicrocode().size());
	}

	// -------------------------------------------------------------------------------------
	// The PDP-11/05
	// -------------------------------------------------------------------------------------

	/**
	 * The window opens on the 11/05, which is the machine it is being written beside, and on the
	 * later of that machine's two board revisions.
	 */
	@Test
	void itOpensOnThePdp1105(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MicrocodePanel panel = Edt.call(() -> new MicrocodePanel(ctx));
		Edt.run(panel::attach);

		assertEquals(MicrocodeSource.PDP1105_F, panel.getSource());
		assertEquals(214, panel.getMicrocode().size());
		assertEquals("M7261 rev F", panel.getMicrocode().getRevision());
		assertEquals("RS-1", panel.getCurrent().getSymbolicTag(), "starting at 000");
	}

	/**
	 * Switching machines changes the table, because the field tables are different sizes.
	 * Switching <i>revision</i> does not, because a revision is not an architecture: the same 18
	 * fields, different bits in fourteen of the microwords.
	 */
	@Test
	void switchingMachineChangesTheTableAndSwitchingRevisionDoesNot(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1144);
		int elevenFortyFour = Edt.call(panel.getTable()::getRowCount);
		assertEquals(FIELDS_START + Pdp1144Fields.ARCHITECTURE.size() + 4, elevenFortyFour);

		Edt.run(() -> panel.chooseSource(MicrocodeSource.PDP1105_F));
		//-- Three rows of provenance rather than four: this document carries no microassembler
		//-- source, so there is no "Source code" row rather than an empty one.
		int revF = Edt.call(panel.getTable()::getRowCount);
		assertEquals(FIELDS_START + Kd11bFields.ARCHITECTURE.size() + 3, revF);

		Edt.run(() -> panel.chooseSource(MicrocodeSource.PDP1105_E));
		assertEquals(revF, Edt.call(panel.getTable()::getRowCount), "same field table, other bits");
		assertEquals("M7261 rev E", panel.getMicrocode().getRevision());
	}

	/** Which way of searching is offered follows what the document actually carries. */
	@Test
	void theSearchModesFollowTheDocument(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1144);
		assertEquals(3, Edt.call(() -> panel.getSearchBySelector().getItemCount()),
			"the 11/44's listing prints its own line numbers");

		Edt.run(() -> panel.chooseSource(MicrocodeSource.PDP1105_F));
		assertEquals(2, Edt.call(() -> panel.getSearchBySelector().getItemCount()),
			"the KD11-B transcription has no line numbers, so it does not offer to search by one");
		assertEquals(214, Edt.call(() -> panel.getSearchBox().getItemCount()));
	}

	/** The chosen revision has to be readable without opening the combo. */
	@Test
	void theWindowTitleNamesTheChosenRevision(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1105_F);
		List<String> titles = new java.util.ArrayList<>();
		Edt.run(() -> panel.setTitleListener(titles::add));
		assertEquals(List.of("Microcode - PDP-11/05 (M7261 rev F)"), titles);

		Edt.run(() -> panel.chooseSource(MicrocodeSource.PDP1105_E));
		assertEquals("Microcode - PDP-11/05 (M7261 rev E)", titles.get(titles.size() - 1));
	}

	/**
	 * The payoff for shipping both revisions: the fourteen microwords that differ say so, in the
	 * two fields they differ in. A wrongly chosen revision has no other symptom at all - every
	 * address resolves and every chain walks.
	 */
	@Test
	void theFieldsTheOtherRevisionDisagreesOnAreMarked(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1105_F);

		//-- U1-1 is one of the five where rev E has AUX=1, CKO=0 and rev F has AUX=0, CKO=1.
		Edt.run(() -> panel.getSearchBySelector().setSelectedItem(MicrocodePanel.SearchBy.TAG));
		Edt.run(() -> panel.searchFor("U1-1"));
		assertEquals(List.of(Kd11bFields.AUX, Kd11bFields.CKO), markedRows(panel));

		//-- And a microword the two sets agree on is not marked, which is 200 of the 214.
		Edt.run(() -> panel.searchFor("B-1"));
		assertEquals(List.of(), markedRows(panel));
	}

	/** The 11/44 has no other revision to disagree with it, so nothing is ever marked. */
	@Test
	void thereIsNothingToCompareThePdp1144Against(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1144);
		Edt.run(() -> panel.searchFor("0461"));
		assertEquals(List.of(), markedRows(panel));
	}

	/**
	 * Where the ALU control is decoded from the instruction the printed ALU field is a don't-care,
	 * and saying it is {@code BL} would be saying the machine does something it does not.
	 */
	@Test
	void aFieldThatIsNotWhatTheMachineDoesSaysSo(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1105_F);
		Edt.run(() -> panel.getSearchBySelector().setSelectedItem(MicrocodePanel.SearchBy.TAG));
		Edt.run(() -> panel.searchFor("U1-1"));

		String alu = infoOf(panel, Kd11bFields.ALU);
		assertTrue(alu.contains("don\'t care"), alu);
		assertTrue(alu.contains("instruction register"), alu);
	}

	/**
	 * A µPC typed off the KM11's lights can be a real control store location that the listing
	 * does not print, and "no microword at 377" on its own reads like a typo when it is not.
	 */
	@Test
	void anAddressTheListingDoesNotPrintSaysWhyItIsMissing(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1105_F);
		Edt.run(() -> panel.searchFor("377"));

		String status = Edt.call(panel::getStatusText);
		assertTrue(status.contains("42 control store locations"), status);
		//-- And an address that does not exist at all is a different sentence.
		Edt.run(() -> panel.searchFor("7000"));
		assertTrue(Edt.call(panel::getStatusText).contains("8 bit control store"),
			Edt.call(panel::getStatusText));
	}

	/**
	 * 73 of the 214 microwords select a microtest, and there the hardware ORs the result into the
	 * next address - so the printed value is a branch base and presenting it as the successor is
	 * stating as fact something that depends on the state of the machine.
	 */
	@Test
	void aBranchingMicrowordsNextAddressIsShownAsABase(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1105_F);
		Edt.run(() -> panel.getSearchBySelector().setSelectedItem(MicrocodePanel.SearchBy.TAG));

		Edt.run(() -> panel.searchFor("RST-1"));
		String branching = Edt.call(panel::getStatusText);
		assertTrue(branching.contains("IR-DECODE"), branching);
		assertTrue(branching.contains("branch base"), branching);
		assertTrue(infoOf(panel, "Next microword").contains("branch base"),
			infoOf(panel, "Next microword"));

		Edt.run(() -> panel.searchFor("B-1"));
		assertFalse(Edt.call(panel::getStatusText).contains("branch base"), "B-1 does not branch");
		//-- And the row shows the decoded address, not the complemented bits the field holds.
		assertEquals("147", infoOf(panel, "Next microword"));
	}

	/** And the 11/05, on one of the fourteen microwords the two board revisions disagree on. */
	@Test
	void renderThePdp1105ForLookingAt(@TempDir Path dir) throws Exception {
		MicrocodePanel panel = panel(dir, MicrocodeSource.PDP1105_F);
		Edt.run(() -> panel.getSearchBySelector().setSelectedItem(MicrocodePanel.SearchBy.TAG));
		Edt.run(() -> panel.searchFor("U1-1"));
		Path file = Edt.call(() -> UiRenderer.renderToFile(panel, WIDTH, HEIGHT,
			Path.of("target", "ui-render", "microcode-panel-1105.png")));
		assertTrue(Files.size(file) > 0);
	}

	/** The labels of the rows the other revision disagrees on. */
	private static List<String> markedRows(MicrocodePanel panel) {
		return Edt.call(() -> {
			List<String> l = new java.util.ArrayList<>();
			for(int i = 0; i < panel.getModel().getRowCount(); i++) {
				MicrocodeTableModel.Row r = panel.getModel().getRow(i);
				if(r.differs())
					l.add(r.label());
			}
			return l;
		});
	}

	@Test
	void theTableGetsTheRoomAndTheControlsOneRow(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> UiRenderer.layOut(panel, WIDTH, HEIGHT));

		Rectangle table = Edt.call(() -> panel.getTable().getParent().getParent().getBounds());
		assertTrue(table.height > HEIGHT / 2, "the table gets the room: " + table);
		//-- Two rows of controls: with three microcodes to choose between, one row needs a window
		//-- about 1100 pixels wide before it fits.
		assertTrue(table.y > 0 && table.y < 110, "the controls are two compact rows: " + table);
		assertTrue(table.x + table.width <= WIDTH, "and it stays inside the panel: " + table);
	}

	/**
	 * And it has to still fit on somebody else's fonts.
	 *
	 * <p>The assertion above is the symptom; this is the cause, and it is worth testing
	 * separately because the symptom only appears on the machine with the wider fonts. A combo box
	 * asks for what its longest item needs and reports that as its <i>minimum</i> too, so a row of
	 * them cannot give way: it overflows instead, and takes the table off the side of the window
	 * with it. This row wanted 886 pixels of the 888 it had at the font of the machine it was
	 * written on, which is not a margin, and CI - a few percent wider - went over.</p>
	 *
	 * <p>So what is asserted is the minimum rather than the preferred width, with enough room left
	 * over that a different font cannot eat it.</p>
	 */
	@Test
	void theControlsFitWithRoomForAWiderFont(@TempDir Path dir) {
		MicrocodePanel panel = panel(dir);
		Edt.run(() -> UiRenderer.layOut(panel, WIDTH, HEIGHT));

		int min = Edt.call(() -> panel.getControls().getMinimumSize().width);
		assertTrue(min <= WIDTH - 100,
			"the controls must squeeze into " + (WIDTH - 100) + " so a wider font still fits: " + min);
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
