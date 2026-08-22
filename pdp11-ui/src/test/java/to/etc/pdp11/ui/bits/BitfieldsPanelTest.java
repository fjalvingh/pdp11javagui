package to.etc.pdp11.ui.bits;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.bits.BitfieldDef;
import to.etc.pdp11.core.bits.BitfieldsDef;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.UiRenderer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A register broken into its named bits, and the two-way editing that makes it useful.
 *
 * <p>The PSW is the example the machine description itself uses: a two-bit current mode, a
 * two-bit previous mode, a three-bit priority and four condition codes, all in one word. Setting
 * priority to 7 should not require working out that it means {@code 0340}.</p>
 */
class BitfieldsPanelTest {
	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	/** The PSW's bits, as {@code pdp11.ini} declares them. */
	private static void definePsw(AppContext ctx) {
		BitfieldsDef def = new BitfieldsDef("Bits.CPU.PSW");
		def.add(new BitfieldDef("Current Mode", "00=kernel, 11=user", 15, 14));
		def.add(new BitfieldDef("Previous Mode", "mode before the last trap", 13, 12));
		def.add(new BitfieldDef("Priority", "Current level of processor priority", 7, 5));
		def.add(BitfieldDef.of("T", "Trace trap", 4));
		def.add(BitfieldDef.of("N", "Negative", 3));
		def.add(BitfieldDef.of("Z", "Zero", 2));
		def.add(BitfieldDef.of("V", "Overflow", 1));
		def.add(BitfieldDef.of("C", "Carry", 0));
		ctx.getBitfieldDefs().add(def);
		ctx.getBitfieldDefs().linkAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776), "Bits.CPU.PSW");
	}

	/** A cell somebody else is showing - a register window's, say. */
	private static MemoryCell pswCell(AppContext ctx, int value) {
		MemoryCellGroup g = ctx.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "CPU");
		MemoryCell mc = g.add(0177776);
		mc.setName("PSW");
		mc.setInfo("Processor Status Word");
		mc.setPdpValue(CellValue.of(value));
		mc.setEditValue(CellValue.of(value));
		return mc;
	}

	@Test
	void itShowsOneRowPerNamedBitfieldWithTheValueSplitOut(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		Edt.run(() -> panel.showCell(pswCell(ctx, 0340)));
		UiRenderer.layOut(panel, 720, 380);

		assertEquals(8, panel.getTable().getRowCount());
		assertEquals("177776", panel.getAddressField().getText());
		assertEquals("000340", panel.getValueField().getText());
		assertTrue(panel.getInfoText().startsWith("CPU . PSW"), panel.getInfoText());

		//-- 0340 is priority 7 and nothing else set.
		assertEquals("Priority", panel.getTable().getValueAt(2, 0));
		assertEquals("7:5", panel.getTable().getValueAt(2, 1));
		assertEquals("7", panel.getTable().getValueAt(2, 3));
		assertEquals("0", panel.getTable().getValueAt(4, 3), "N is clear");
	}

	@Test
	void typingTheWholeWordUpdatesEveryField(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		Edt.run(() -> panel.showCell(pswCell(ctx, 0)));

		Edt.run(() -> panel.getValueField().setText("000014"));
		//-- 014 is N and Z set, priority 0.
		assertEquals("1", panel.getTable().getValueAt(4, 3), "N");
		assertEquals("1", panel.getTable().getValueAt(5, 3), "Z");
		assertEquals("0", panel.getTable().getValueAt(2, 3), "priority");
	}

	@Test
	void typingAFieldUpdatesTheWholeWord(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		Edt.run(() -> panel.showCell(pswCell(ctx, 0)));

		//-- Priority 7, which is the whole point of this window: no working out that it is 0340.
		Edt.run(() -> panel.getTable().setValueAt("7", 2, 3));
		assertEquals(0340, panel.getCell().getEditValue().word());
		assertEquals("000340", panel.getValueField().getText());

		//-- And a second field on top of the first, leaving it alone.
		Edt.run(() -> panel.getTable().setValueAt("1", 7, 3));
		assertEquals(0341, panel.getCell().getEditValue().word(), "carry set, priority kept");
	}

	@Test
	void aFieldTooBigForItsWidthIsRefusedRatherThanCorruptingItsNeighbours(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		Edt.run(() -> panel.showCell(pswCell(ctx, 0)));

		//-- Priority is three bits, so 7 is its maximum. 10 octal would overflow into the T bit.
		Edt.run(() -> panel.getTable().setValueAt("10", 2, 3));
		assertEquals(0, panel.getCell().getEditValue().word());
	}

	@Test
	void aChangedFieldAndTheChangedWordBothShowIt(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		Edt.run(() -> panel.showCell(pswCell(ctx, 0)));
		Edt.run(() -> panel.getTable().setValueAt("7", 2, 3));

		assertEquals(UiColors.EDITED_BACKGROUND, panel.backgroundOf(2, 3), "the field that changed");
		assertNotSame(UiColors.EDITED_BACKGROUND, panel.backgroundOf(4, 3), "and not the ones that did not");
		assertEquals(UiColors.EDITED_BACKGROUND, panel.getValueField().getBackground(), "and the word itself");
	}

	@Test
	void anAddressWithNoDefinitionsSaysSoInsteadOfShowingAnEmptyTable(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		//-- Nothing selected at all.
		assertTrue(panel.getNoDefinitionsText().contains("Select a register"), panel.getNoDefinitionsText());

		//-- And an address the description says nothing about.
		MemoryCellGroup g = ctx.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "Memory");
		MemoryCell mc = g.add(01000);
		Edt.run(() -> panel.showCell(mc));
		assertTrue(panel.getNoDefinitionsText().contains("No bit field definitions"), panel.getNoDefinitionsText());
		assertEquals(0, panel.getTable().getRowCount());
	}

	/**
	 * The window edits its own cell at the same address, so experimenting with the bits does not
	 * change what another window is showing until it is deposited - and once it is, the
	 * propagation bus tells that window anyway.
	 */
	@Test
	void itEditsItsOwnCopyAndStaysOnTheBus(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		MemoryCell theirs = pswCell(ctx, 0);
		Edt.run(() -> panel.showCell(theirs));

		assertNotSame(theirs, panel.getCell(), "its own cell");
		assertEquals(theirs.getAddr(), panel.getCell().getAddr(), "at the same address");

		Edt.run(() -> panel.getTable().setValueAt("7", 2, 3));
		assertEquals(0, theirs.getEditValue().word(), "the other window is undisturbed");

		//-- But the two are the same location as far as propagation is concerned, which is what
		//-- makes a deposit here show up there. Re-pointing the window must keep that true.
		Edt.run(() -> {
			panel.getCell().setPdpValue(CellValue.of(0777));
			ctx.getMemoryCellGroups().syncMemoryCells(panel.getCell());
		});
		assertEquals(0777, theirs.getPdpValue().word(), "the index still says these are one register");
	}

	@Test
	void followingTheSelectionMovesToTheNewAddress(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		Edt.run(panel::attach);

		MemoryCellGroup g = ctx.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL16, "CPU");
		MemoryCell psw = g.add(0177776);
		psw.setName("PSW");
		MemoryCell other = g.add(0177570);
		other.setName("SWITCHES");

		Edt.run(() -> ctx.getCellSelection().select(psw));
		assertEquals("177776", panel.getAddressField().getText());
		assertEquals(8, panel.getTable().getRowCount());

		Edt.run(() -> ctx.getCellSelection().select(other));
		assertEquals("177570", panel.getAddressField().getText());
		assertEquals(0, panel.getTable().getRowCount(), "nothing defines the switch register's bits");
		assertFalse(panel.getNoDefinitionsText().isEmpty());
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		Edt.run(() -> panel.showCell(pswCell(ctx, 0340)));
		Edt.run(() -> panel.getTable().setValueAt("1", 5, 3));
		Path file = UiRenderer.renderToFile(panel, 720, 340, Path.of("target", "ui-render", "bitfields-panel.png"));
		assertTrue(Files.size(file) > 0);
	}
}
