package to.etc.pdp11.ui.mem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.MainPanel;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of what is waiting to be deposited, and the count in the main window's status bar.
 */
class PendingChangesPanelTest {
	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	@Test
	void everyPendingWordIsListedWithWhoMadeIt(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroups groups = ctx.getMemoryCellGroups();
		groups.addGroup(MemoryAddressType.PHYSICAL22, "Memory load").add(02000).setEditValue(CellValue.of(2));
		groups.addGroup(MemoryAddressType.PHYSICAL22, "Memory 1").add(01000).setEditValue(CellValue.of(1));

		PendingChangesPanel panel = Edt.call(() -> new PendingChangesPanel(ctx));
		UiRenderer.layOut(panel, 560, 420);

		assertEquals(2, panel.getTable().getRowCount());
		assertEquals("00001000", panel.getTable().getValueAt(0, 0));
		assertEquals("Memory 1", panel.getTable().getValueAt(0, 3));
		assertEquals("Memory load", panel.getTable().getValueAt(1, 3));
		assertTrue(panel.getStatusText().startsWith("2 words"), panel.getStatusText());
		assertFalse(panel.getDepositButton().isEnabled(), "there is no machine to deposit to");
	}

	@Test
	void anEditMadeWhileItIsOpenAppears(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		PendingChangesPanel panel = Edt.call(() -> new PendingChangesPanel(ctx));
		Edt.run(panel::attach);
		assertEquals(0, panel.getTable().getRowCount());

		ctx.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL22, "Memory 1").add(01000)
			.setEditValue(CellValue.of(1));
		Edt.run(() -> {
		});                                                     // let the coalesced reload land

		assertEquals(1, panel.getTable().getRowCount());
		Edt.run(panel::detach);
	}

	@Test
	void discardingTheSelectionGivesThoseWordsBackToTheMachine(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroup g = ctx.getMemoryCellGroups().addGroup(MemoryAddressType.PHYSICAL22, "Memory 1");
		MemoryCell a = g.add(01000);
		MemoryCell b = g.add(01002);
		a.setEditValue(CellValue.of(1));
		b.setEditValue(CellValue.of(2));
		PendingChangesPanel panel = Edt.call(() -> new PendingChangesPanel(ctx));

		Edt.run(() -> {
			panel.getTable().setRowSelectionInterval(0, 0);
			panel.getDiscardSelectedButton().doClick();
		});

		assertFalse(a.isEdited());
		assertTrue(b.isEdited());
		assertEquals(1, panel.getTable().getRowCount());

		Edt.run(() -> panel.getDiscardAllButton().doClick());
		assertFalse(b.isEdited());
		assertEquals("Nothing is waiting to be deposited", panel.getStatusText());
	}

	@Test
	void theStatusBarSaysHowManyAndOnlyWhenThereAreAny() {
		MainPanel panel = Edt.call(MainPanel::new);
		UiRenderer.layOut(panel, 1000, 600);
		assertFalse(panel.getPendingButton().isVisible());

		Edt.run(() -> panel.showPending(3));
		assertTrue(panel.getPendingButton().isVisible());
		assertEquals("3 words to deposit", panel.getPendingButton().getText());

		Edt.run(() -> panel.showPending(0));
		assertFalse(panel.getPendingButton().isVisible());
	}

	/** Rendered for looking at, as every panel is. */
	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		MemoryCellGroups groups = ctx.getMemoryCellGroups();
		MemoryCellGroup asm = groups.addGroup(MemoryAddressType.VIRTUAL, "MACRO-11 code");
		asm.add(01000, 6);
		for(MemoryCell mc : asm.getCells()) {
			mc.setEditValue(CellValue.of(0240));
		}
		MemoryCell read = groups.addGroup(MemoryAddressType.PHYSICAL22, "Memory 1").add(04000);
		read.setPdpValue(CellValue.of(5));
		read.setEditValue(CellValue.of(6));
		groups.getSharedMemory().markRun();
		PendingChangesPanel panel = Edt.call(() -> new PendingChangesPanel(ctx));
		Path png = UiRenderer.renderToFile(panel, 560, 420, Path.of("target", "ui-render", "pending-changes.png"));
		assertTrue(java.nio.file.Files.size(png) > 0);
	}
}
