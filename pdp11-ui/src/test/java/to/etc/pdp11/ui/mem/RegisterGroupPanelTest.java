package to.etc.pdp11.ui.mem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiColors;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.JTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The register list: named cells, one per row, with room for what the machine description knows
 * about each of them.
 *
 * <p>The other half of the reusable-frame pair PLAN.md §3 asks for. Where the grid lays cells
 * out by address, this shows them in the group's own order - which is what lets one address
 * appear more than once under different names, as several device registers genuinely do.</p>
 */
class RegisterGroupPanelTest {
	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	/** A device group shaped like one from a machine description. */
	private static MemoryCellGroup deviceGroup(MemoryCellGroups groups) {
		MemoryCellGroup g = groups.addGroup(MemoryAddressType.PHYSICAL16, "DL11");
		g.setUsageTag("machine");
		g.setGroupInfo("Serial line interface");
		named(g, 0177560, "RCSR", "Receiver status");
		named(g, 0177562, "RBUF", "Receiver buffer");
		named(g, 0177564, "XCSR", "Transmitter status");
		named(g, 0177566, "XBUF", "Transmitter buffer");
		return g;
	}

	private static MemoryCell named(MemoryCellGroup g, long addr, String name, String info) {
		MemoryCell mc = g.add(addr);
		mc.setName(name);
		mc.setInfo(info);
		return mc;
	}

	@Test
	void oneRowPerRegisterWithItsNameAddressValueAndDescription(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroup g = deviceGroup(ctx.getMemoryCellGroups());
		g.cell(0).setPdpValue(CellValue.of(0100));
		g.cell(0).setEditValue(CellValue.of(0100));
		RegisterGroupPanel panel = Edt.call(() -> new RegisterGroupPanel(ctx, g));
		UiRenderer.layOut(panel, 760, 320);

		JTable table = panel.getList().getTable();
		assertEquals(4, table.getRowCount());
		assertEquals(4, table.getColumnCount());
		assertEquals("Register", table.getColumnName(0));
		assertEquals("RCSR", table.getValueAt(0, 0));
		//-- Six digits: a 16-bit address is six octal digits wide, and these groups are 16-bit
		//-- whatever machine is on the other end.
		assertEquals("177560", table.getValueAt(0, 1));
		assertEquals("000100", table.getValueAt(0, 2));
		assertEquals("Receiver status", table.getValueAt(0, 3));
		//-- Never read is "?", not a zero that looks like a real value.
		assertEquals("?", table.getValueAt(1, 2));
	}

	@Test
	void onlyTheValueColumnCanBeEdited(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		RegisterGroupPanel panel = Edt.call(() -> new RegisterGroupPanel(ctx, deviceGroup(ctx.getMemoryCellGroups())));
		JTable table = panel.getList().getTable();
		assertFalse(table.isCellEditable(0, 0), "a register's name is not a thing to type over");
		assertFalse(table.isCellEditable(0, 1));
		assertTrue(table.isCellEditable(0, 2));
		assertFalse(table.isCellEditable(0, 3));
	}

	@Test
	void typingAValueMarksItAndHoldsOffIncomingValues(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroup g = deviceGroup(ctx.getMemoryCellGroups());
		RegisterGroupPanel panel = Edt.call(() -> new RegisterGroupPanel(ctx, g));
		assertTrue(g.isPdpOverwritesEdit());

		Edt.run(() -> panel.getList().getTable().setValueAt("000200", 0, 2));

		assertEquals(0200, g.cell(0).getEditValue().word());
		assertEquals(UiColors.EDITED_BACKGROUND, panel.getList().backgroundOf(0, 2));
		assertFalse(g.isPdpOverwritesEdit());
		assertTrue(panel.getInfoText().contains("1 changed"), panel.getInfoText());
	}

	/**
	 * The RX211 declares one address six times under six names because the controller
	 * reinterprets it at each stage of a transfer. All six are rows; they hold the same word.
	 */
	@Test
	void oneAddressUnderSeveralNamesIsSeveralRowsThatAgree(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroups groups = ctx.getMemoryCellGroups();
		MemoryCellGroup g = groups.addGroup(MemoryAddressType.PHYSICAL16, "RX211");
		named(g, 0177172, "RX2TA", "Track address");
		named(g, 0177172, "RX2SA", "Sector address");
		RegisterGroupPanel panel = Edt.call(() -> new RegisterGroupPanel(ctx, g));

		assertEquals(2, panel.getList().getTable().getRowCount());
		//-- A value arriving for one is a value for both, because it is one register. On the
		//-- event thread, because that is where the view updates itself from the change.
		Edt.run(() -> {
			g.cell(0).setPdpValue(CellValue.of(077));
			groups.syncMemoryCells(g.cell(0));
		});
		assertEquals(077, g.cell(1).getPdpValue().word());
		assertEquals("000077", panel.getList().getTable().getValueAt(1, 2));
	}

	@Test
	void theSelectedCellIsAnnouncedRatherThanPushedAtAnotherWindow(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroup g = deviceGroup(ctx.getMemoryCellGroups());
		RegisterGroupPanel panel = Edt.call(() -> new RegisterGroupPanel(ctx, g));
		AtomicReference<MemoryCell> selected = new AtomicReference<>();
		panel.getList().setOnSelect(selected::set);

		Edt.run(() -> panel.getList().getTable().setRowSelectionInterval(1, 1));
		//-- This is SyncBitfieldForm without the reach-in: the list says which cell, and the
		//-- Bitfields window will subscribe when it exists.
		assertSame(g.cell(1), selected.get());
	}

	@Test
	void disconnectingLeavesNothingPointingAtTheOldCells(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroup g = deviceGroup(ctx.getMemoryCellGroups());
		RegisterGroupPanel panel = Edt.call(() -> new RegisterGroupPanel(ctx, g));
		Edt.run(() -> panel.getList().disconnect());

		assertEquals(0, panel.getList().getRowCount());
		assertNull(panel.getList().getGroup());
		//-- And it really has unsubscribed: a value arriving now must not reach a dead view.
		Edt.run(() -> {
			g.cell(0).setPdpValue(CellValue.of(0123));
			ctx.getMemoryCellGroups().syncMemoryCells(g.cell(0));
		});
		assertEquals(0, panel.getList().getRowCount());
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroup g = deviceGroup(ctx.getMemoryCellGroups());
		int v = 0100;
		for(MemoryCell mc : g.getCells()) {
			mc.setPdpValue(CellValue.of(v));
			mc.setEditValue(CellValue.of(v));
			v += 0202;
		}
		RegisterGroupPanel panel = Edt.call(() -> new RegisterGroupPanel(ctx, g));
		Edt.run(() -> panel.getList().getTable().setValueAt("000007", 2, 2));
		Path file = UiRenderer.renderToFile(panel, 760, 300, Path.of("target", "ui-render", "register-group.png"));
		assertTrue(Files.size(file) > 0);
	}
}
