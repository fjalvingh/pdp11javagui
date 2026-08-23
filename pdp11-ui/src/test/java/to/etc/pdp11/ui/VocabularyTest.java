package to.etc.pdp11.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.bits.BitfieldsPanel;
import to.etc.pdp11.ui.dump.MemoryDumperPanel;
import to.etc.pdp11.ui.exec.ExecutionPanel;
import to.etc.pdp11.ui.load.MemoryLoaderPanel;
import to.etc.pdp11.ui.macro11.AssemblerPanel;
import to.etc.pdp11.ui.mem.MemoryPanel;
import to.etc.pdp11.ui.mem.RegisterGroupPanel;
import to.etc.pdp11.ui.mmu.MmuPanel;
import to.etc.pdp11.ui.scan.IoPageScannerPanel;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application says the same thing the same way in every window.
 *
 * <p>The two actions this whole program exists for are <b>examine</b> - read a word out of the
 * machine - and <b>deposit</b> - write one in. They had four names and two orderings between
 * them: the Dumper said "Read from machine", the MMU window said "Read the MMU registers", the
 * Bitfields window said just "Examine", the Register Group window said "Examine register" where
 * its two siblings said "Examine cell", and the Assembler's code tab called deposit-all "Load
 * into machine" and put it <i>before</i> "Deposit changed" where every other window puts it
 * after.</p>
 *
 * <p>None of that is a bug in the sense that anything misbehaves. It is worse than a bug: it is
 * four names for one idea in a program whose entire subject is that one idea, and it can only be
 * caught by opening nine windows and reading them side by side. Which is what this does.</p>
 */
class VocabularyTest {
	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	/** Every button in a laid-out panel, in the order they appear in the tree. */
	private static List<AbstractButton> buttonsOf(JComponent panel) {
		UiRenderer.layOut(panel, 1100, 560);
		List<AbstractButton> l = new ArrayList<>();
		collect(panel, l);
		return l;
	}

	private static void collect(java.awt.Container c, List<AbstractButton> into) {
		for(java.awt.Component child : c.getComponents()) {
			if(child instanceof AbstractButton b)
				into.add(b);
			if(child instanceof java.awt.Container inner)
				collect(inner, into);
		}
	}

	private static List<String> labelsOf(JComponent panel) {
		List<String> l = new ArrayList<>();
		for(AbstractButton b : buttonsOf(panel)) {
			if(b.getText() != null && !b.getText().isBlank())
				l.add(b.getText());
		}
		return l;
	}

	private static MemoryCellGroup deviceGroup(AppContext ctx) {
		MemoryCellGroup g = ctx.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "DL11");
		g.setUsageTag("machine");
		g.add(0177560).setName("RCSR");
		g.add(0177562).setName("RBUF");
		return g;
	}

	/** Every window that reads the machine calls it examining, and nothing else does. */
	@Test
	void readingTheMachineIsCalledExaminingEverywhere(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		List<List<String>> everyWindow = List.of(
			labelsOf(new MemoryPanel(ctx, "1")),
			labelsOf(new MemoryDumperPanel(ctx)),
			labelsOf(new MmuPanel(ctx)),
			labelsOf(new BitfieldsPanel(ctx)),
			labelsOf(new RegisterGroupPanel(ctx, deviceGroup(ctx))),
			labelsOf(new IoPageScannerPanel(ctx)));

		for(List<String> labels : everyWindow) {
			assertTrue(labels.stream().anyMatch(t -> t.startsWith("Examine")),
				"no Examine button among " + labels);
			for(String t : labels) {
				assertFalse(t.startsWith("Read ") || t.equals("Read from machine"),
					"\"" + t + "\" is another name for examining, in " + labels);
			}
		}
	}

	/** And the scope is named the same way: all of it, or the one that is selected. */
	@Test
	void examineComesInExactlyTwoSizesWithOneNameEach(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		for(List<String> labels : List.of(
			labelsOf(new MemoryPanel(ctx, "1")),
			labelsOf(new RegisterGroupPanel(ctx, deviceGroup(ctx))),
			labelsOf(new IoPageScannerPanel(ctx)),
			labelsOf(new BitfieldsPanel(ctx)),
			labelsOf(new MmuPanel(ctx)),
			labelsOf(new MemoryDumperPanel(ctx)))) {
			for(String t : labels) {
				if(!t.startsWith("Examine"))
					continue;
				assertTrue(t.equals("Examine all") || t.equals("Examine cell"),
					"\"" + t + "\" is a third name for a scope that has two");
			}
		}
	}

	/**
	 * Deposit is offered in one order: read it, write what changed, write the lot. The
	 * Assembler's code tab used to lead with deposit-all under a name of its own.
	 */
	@Test
	void depositIsNamedAndOrderedTheSameWayEverywhere(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		for(JComponent panel : List.of(
			new MemoryPanel(ctx, "1"),
			new RegisterGroupPanel(ctx, deviceGroup(ctx)),
			new MemoryLoaderPanel(ctx),
			Edt.call(() -> new AssemblerPanel(ctx)))) {
			List<String> labels = labelsOf(panel);
			int changed = labels.indexOf("Deposit changed");
			int all = labels.indexOf("Deposit all");
			assertTrue(changed >= 0, "no \"Deposit changed\" in " + labels);
			assertTrue(all >= 0, "no \"Deposit all\" in " + labels + " - another name for it?");
			assertTrue(changed < all, "deposit-all comes after deposit-changed, not before: " + labels);
		}
	}

	/**
	 * Verify is a button where it is offered at all. It was a right-click item in the Memory
	 * window - the only right-click menu in the application, so the one surface nobody would
	 * think to look at - and a toolbar button in its two siblings.
	 */
	@Test
	void verifyIsAButtonInEveryWindowThatOffersIt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		for(JComponent panel : List.of(
			new MemoryPanel(ctx, "1"),
			new MemoryLoaderPanel(ctx),
			Edt.call(() -> new AssemblerPanel(ctx)))) {
			assertTrue(labelsOf(panel).contains("Verify"), "no Verify button in " + labelsOf(panel));
		}
	}

	/**
	 * A button's label says what it does. "Reset" also deposited the Start PC into R7 - a button
	 * named after half of what it did, silently writing a register - and "Set/show" only ever
	 * set, the "show" being the disassembler following along in a different window.
	 */
	@Test
	void theExecutionButtonsAreNamedAfterWhatTheyDo(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		List<String> labels = labelsOf(new ExecutionPanel(ctx));
		assertTrue(labels.contains("Reset and set PC"), labels.toString());
		assertTrue(labels.contains("Set PC"), labels.toString());
		assertFalse(labels.contains("Reset"), "\"Reset\" does not say that it also writes R7");
		assertFalse(labels.contains("Set/show"), "it does not show anything");
	}

	/** The counts a window prints and the counts it takes are in the same base. */
	@Test
	void theMemoryWindowCountsWordsInOneBase(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel panel = new MemoryPanel(ctx, "1");
		UiRenderer.layOut(panel, 900, 460);
		assertEquals(String.valueOf(panel.getGroup().size()), panel.getBlockSizeField().getText(),
			"the field and the group agree");
		assertTrue(panel.getInfoText().startsWith(panel.getGroup().size() + " words"),
			"and so does the status line under it: " + panel.getInfoText());
	}
}
