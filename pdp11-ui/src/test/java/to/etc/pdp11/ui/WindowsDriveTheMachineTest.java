package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.console.ConsoleRunMode;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.ui.disas.DisassemblerPanel;
import to.etc.pdp11.ui.exec.ExecutionPanel;
import to.etc.pdp11.ui.mem.MemoryPanel;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path, end to end: a button on a panel, onto the command thread, through a console
 * protocol, into a simulated machine, and back into the cells the windows show.
 *
 * <p>This is the test that says phase 5 is finished - PLAN.md's "you can connect, examine and
 * deposit memory, run and single-step, and disassemble" - and it runs with no hardware, no SimH,
 * no serial port and no display, because the machine is simulated inside this JVM and every
 * window is a {@code JPanel}.</p>
 *
 * <p>Each action is queued on the command thread rather than run where it was asked for, so the
 * assertions wait for a condition rather than reading a value straight after asking for it. That
 * is not test scaffolding around an awkward design - it is the design: a console call on the
 * event thread deadlocks the application, which is the first rule in PLAN.md §1.</p>
 */
class WindowsDriveTheMachineTest {
	private static final long TIMEOUT_MS = 10_000;

	private static AppContext connected(Path dir, ConsoleProtocol protocol) throws Exception {
		AppContext ctx = TestContext.create(dir);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(protocol));
		return ctx;
	}

	/** Wait for something the command thread will get round to. */
	private static void until(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean())
				return;
			try {
				Thread.sleep(10);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(x);
			}
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	@Test
	void theMemoryWindowExaminesAndDepositsThroughARealConsole(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			MemoryPanel panel = Edt.call(() -> new MemoryPanel(ctx, "1"));
			Edt.run(() -> {
				panel.getBlockSizeField().setText("10");
				panel.getStartAddressField().setText("1000");
				panel.getStartAddressField().postActionEvent();
			});
			assertEquals(01000, panel.getGroup().getRange().lo());

			//-- Setting the range examines it, because a window showing "?" for everything until
			//-- you press a button is not showing memory.
			until("the first examine to finish",
				() -> panel.getGroup().cell(0).getPdpValue().isKnown());

			//-- Type a value and deposit it, which is the other half of what this window is for.
			MemoryCell cell = panel.cellAt(0, 1);
			assertNotNull(cell);
			Edt.run(() -> panel.getGrid().getTable().setValueAt("123456", 0, 1));
			assertTrue(cell.isEdited());
			Edt.run(() -> panel.getGrid().depositAll(true, null));
			until("the deposit to reach the machine", () -> !cell.isEdited());

			//-- And the machine really has it: forget everything and read it back.
			Edt.run(() -> {
				panel.getGroup().invalidate();
				panel.getGrid().examineAll(false, null);
			});
			until("the re-examine", () -> cell.getPdpValue().isKnown());
			assertEquals(0123456, cell.getPdpValue().word(), "what was deposited is what is there");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * A deposit in one window shows up in another looking at the same address - which is the
	 * whole purpose of the propagation bus, and the thing the Pascal's single-delegate
	 * {@code OnMemoryCellChange} could only do for one subscriber at a time.
	 */
	@Test
	void twoMemoryWindowsOnOneAddressAgree(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			MemoryPanel first = Edt.call(() -> new MemoryPanel(ctx, "1"));
			MemoryPanel second = Edt.call(() -> new MemoryPanel(ctx, "2"));
			MemoryCell theirs = second.cellAt(0, 1);

			Edt.run(() -> first.getGrid().getTable().setValueAt("007070", 0, 1));
			Edt.run(() -> first.getGrid().depositAll(true, null));

			//-- Two hops, on two threads: the value reaches the cell on the command thread and
			//-- the view copies it into what it displays on the event thread. Waiting only for
			//-- the first and then asserting the second is a race the test can lose, so wait for
			//-- the one actually being asserted.
			until("the value to reach the other window",
				() -> theirs.getPdpValue().isKnown() && theirs.getPdpValue().word() == 07070);
			until("the other window to show it",
				() -> theirs.getEditValue().isKnown() && theirs.getEditValue().word() == 07070);
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void singleSteppingMovesThePcAndTheDisassemblerFollowsIt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel execution = Edt.call(() -> new ExecutionPanel(ctx));
			DisassemblerPanel disassembler = Edt.call(() -> new DisassemblerPanel(ctx));
			Edt.run(() -> {
				execution.attach();
				//-- In a frame, because a window nobody has open deliberately does not read
				//-- memory on every stop.
				javax.swing.JFrame frame = new javax.swing.JFrame();
				frame.setContentPane(disassembler);
				disassembler.attach();
			});

			//-- Put a couple of instructions somewhere and start there.
			Address code = Address.of(ctx.getConnectionManager().getConsole().physicalAddressType(), 01000);
			ctx.getConnectionManager().getConnection().run(() -> {
				ctx.getConnectionManager().getConsole().deposit(code, 0005000);          // clr r0
				ctx.getConnectionManager().getConsole().deposit(code.plus(2), 0005200);  // inc r0
			});

			Edt.run(() -> {
				execution.getStartPcField().setText("1000");
				execution.getCurrentPcField().setText("1000");
			});
			//-- Set the PC through the window, exactly as the button does.
			Edt.run(() -> clickSetPc(execution));
			until("the PC to be set", () -> ctx.getMachineState().getPc() != null
				&& ctx.getMachineState().getPc().val() == 01000);

			Edt.run(() -> click(execution.getSingleStepButton()));
			until("the machine to report where it stopped", () -> ctx.getMachineState().getPc() != null
				&& ctx.getMachineState().getPc().val() != 01000);

			//-- The disassembler was never told about the execution window and still moved.
			Address pc = ctx.getMachineState().getPc();
			until("the disassembler to centre on the new PC",
				() -> disassembler.getGroup().getRange().mayContain(pc.val()));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * ODT reports a different feature set depending on the switch, and the console has to be
	 * told which way it is turned before it will do anything at all.
	 */
	@Test
	void anOdtMachineIsDrivenWithItsRunHaltSwitch(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.ODT_16);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			assertTrue(panel.getSwitchPanel().isVisible());

			//-- With the switch at HALT, ODT can reset and step.
			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.HALT);
			Edt.run(panel::updateDisplay);
			Edt.run(() -> {
				panel.getCurrentPcField().setText("1000");
				clickSetPc(panel);
			});
			until("the PC to be set on the ODT machine", () -> ctx.getMachineState().getPc() != null
				&& ctx.getMachineState().getPc().val() == 01000);
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aMemoryWindowThatWasNeverConnectedShowsNothingRatherThanZeroes(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		MemoryPanel panel = Edt.call(() -> new MemoryPanel(ctx, "1"));
		for(MemoryCell mc : panel.getGroup().getCells()) {
			assertEquals(CellValue.UNKNOWN, mc.getPdpValue());
		}
	}

	private static void click(javax.swing.AbstractButton button) {
		assertTrue(button.isEnabled(), button.getText() + " should be enabled");
		button.doClick();
	}

	/** The Set/show button, found by its text so the test presses what the user presses. */
	private static void clickSetPc(java.awt.Container panel) {
		for(java.awt.Component c : panel.getComponents()) {
			if(c instanceof javax.swing.AbstractButton b && "Set/show".equals(b.getText())) {
				click(b);
				return;
			}
		}
		throw new AssertionError("No Set/show button on " + panel);
	}
}
