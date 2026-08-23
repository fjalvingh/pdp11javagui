package to.etc.pdp11.ui.bits;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.bits.BitfieldDef;
import to.etc.pdp11.core.bits.BitfieldsDef;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.JButton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

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
	private static final long TIMEOUT_MS = 30_000;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static void until(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean())
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

	/**
	 * Examine and Deposit are dead with nothing connected, and live again once there is.
	 *
	 * <p>Typing bits and reading off what they mean is the rest of the window and stays
	 * available offline; only the two round trips go. Before this, either button offline was a
	 * modal "Not connected to a machine" dialog.</p>
	 */
	@Test
	void examineAndDepositFollowTheConnection(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		for(JButton b : panel.getMachineControls())
			assertFalse(b.isEnabled(), b.getText() + " needs a machine");
		//-- The value field is still typeable: working out what 0340 means needs no machine.
		assertTrue(panel.getValueField().isEnabled());

		Edt.run(panel::attach);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			until("the buttons to arm",
				() -> panel.getMachineControls().stream().allMatch(JButton::isEnabled));
			ctx.getConnectionManager().disconnect();
			until("the buttons to go dead again",
				() -> panel.getMachineControls().stream().noneMatch(JButton::isEnabled));
		} finally {
			ctx.getConnectionManager().close();
			Edt.run(panel::detach);
		}
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

	/**
	 * Bits being composed here are not thrown away by somebody else's examine.
	 *
	 * <p>This window's group sits on the propagation bus so that a deposit here reaches every
	 * window showing the same register - and that bus runs both ways. With
	 * {@code pdpOverwritesEdit} left at its default, an examine of the same address from any
	 * other window propagated the machine's value in and the cell listener copied it straight
	 * over the half-composed word, silently. Every other edit-holding view opts out while it
	 * holds an edit; this one now does too.</p>
	 */
	@Test
	void anExamineElsewhereDoesNotWipeTheBitsBeingComposed(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		MemoryCell theirs = pswCell(ctx, 0);
		Edt.run(() -> panel.showCell(theirs));

		//-- Priority 7, typed here and not deposited: what the window is for.
		Edt.run(() -> panel.getTable().setValueAt("7", 2, 3));
		assertEquals(0340, panel.getCell().getEditValue().word());

		//-- Meanwhile another window examines the same register and the machine says 0100.
		Edt.run(() -> {
			theirs.setPdpValue(CellValue.of(0100));
			ctx.getMemoryCellGroups().syncMemoryCells(theirs);
		});

		assertEquals(0340, panel.getCell().getEditValue().word(), "the composition is still there");
		assertEquals("000340", panel.getValueField().getText());
		assertEquals("7", panel.getTable().getValueAt(2, 3));

		//-- And once it has been deposited there is nothing left to protect, so the window
		//-- follows the machine again like any other view.
		Edt.run(() -> {
			panel.getCell().setDeposited();
			panel.refreshValue();
			theirs.setPdpValue(CellValue.of(0200));
			ctx.getMemoryCellGroups().syncMemoryCells(theirs);
		});
		assertEquals(0200, panel.getCell().getEditValue().word(), "nothing edited, so it tracks");
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

	/**
	 * The whole point of making the address field editable: reaching a register without first
	 * having to find it in another window.
	 */
	@Test
	void typingAnAddressPointsTheWindowAtIt(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));

		assertTrue(panel.getAddressField().isEditable(), "the address can be typed in");

		Edt.run(() -> panel.showAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177776)));
		assertEquals("177776", panel.getAddressField().getText());
		assertEquals(8, panel.getTable().getRowCount(), "the PSW's bits, with nothing selected anywhere");
		assertEquals("?", panel.getValueField().getText(), "nothing read it yet");
		assertTrue(panel.getInfoText().contains("Bits.CPU.PSW"), panel.getInfoText());

		//-- And typing over it moves on, dropping what belonged to the old register.
		Edt.run(() -> panel.getValueField().setText("000340"));
		assertEquals(0340, panel.getCell().getEditValue().word());
		Edt.run(() -> panel.showAddress(Address.of(MemoryAddressType.PHYSICAL16, 0177570)));
		assertEquals("177570", panel.getAddressField().getText());
		assertFalse(panel.getCell().getEditValue().isKnown(), "the old value did not come along");
		assertTrue(panel.getNoDefinitionsText().contains("No bit field definitions"), panel.getNoDefinitionsText());
	}

	/** A typed address is the same location as everybody else's, so the propagation bus still works. */
	@Test
	void aTypedAddressStaysOnTheBus(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		definePsw(ctx);
		BitfieldsPanel panel = Edt.call(() -> new BitfieldsPanel(ctx));
		MemoryCell theirs = pswCell(ctx, 0);

		Edt.run(() -> panel.showAddress(theirs.getAddr()));
		assertNotSame(theirs, panel.getCell());

		Edt.run(() -> {
			panel.getCell().setPdpValue(CellValue.of(0777));
			ctx.getMemoryCellGroups().syncMemoryCells(panel.getCell());
		});
		assertEquals(0777, theirs.getPdpValue().word());
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
