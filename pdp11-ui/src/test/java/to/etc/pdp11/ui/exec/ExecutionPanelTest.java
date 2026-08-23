package to.etc.pdp11.ui.exec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.console.ConsoleRunMode;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run control, and specifically which buttons are alive.
 *
 * <p>That is the substance of this window and the easiest thing in it to get wrong, because it
 * depends on the console's feature set and the machine's state at the same time - and getting it
 * wrong produces a button that silently does nothing rather than an error. Each case here is one
 * row of {@code UpdateDisplay} ({@code FormExecuteU.pas:281-380}).</p>
 *
 * <p>Everything runs against a machine simulated in this JVM, so it needs no hardware, no SimH
 * and no serial port.</p>
 */
class ExecutionPanelTest {
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

	/** Every state the machine passes through, in order, recorded from the event thread. */
	private static List<MachineState.ExecutionState> record(AppContext ctx) {
		List<MachineState.ExecutionState> seen = new CopyOnWriteArrayList<>();
		ctx.getMachineState().addListener(state -> seen.add(state.getState()));
		return seen;
	}

	private static AppContext connected(Path dir, ConsoleProtocol protocol) throws Exception {
		AppContext ctx = TestContext.create(dir);
		ctx.getConnectionManager().connect(ConnectionProfile.simulated(protocol));
		return ctx;
	}

	@Test
	void withNoMachineNothingIsOffered(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
		UiRenderer.layOut(panel, 640, 260);

		assertEquals("Not connected", panel.getStateText());
		assertFalse(panel.getSingleStepButton().isEnabled());
		assertFalse(panel.getHaltButton().isEnabled(), "there is nothing to halt");
		assertFalse(panel.getSwitchPanel().isVisible(), "and no switch to be in a position");
	}

	@Test
	void simhOffersEverythingItCanDo(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			UiRenderer.layOut(panel, 640, 260);

			assertTrue(panel.getSingleStepButton().isEnabled());
			assertTrue(panel.getHaltButton().isEnabled());
			//-- SimH is a program, not a machine with a front panel.
			assertFalse(panel.getSwitchPanel().isVisible());
			assertEquals("Machine: state unknown", panel.getStateText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * ODT's feature set genuinely changes with the switch: {@code nnnG} and {@code P} mean
	 * "reset" and "step" with the machine halted and "run" and "continue" with it enabled
	 * ({@code OdtConsole}, from {@code ConsolePDP11ODTU.pas:330-352}). So the buttons have to
	 * change with it too.
	 */
	@Test
	void odtShowsTheRunHaltSwitchAndFollowsIt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.ODT_16);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			UiRenderer.layOut(panel, 640, 260);
			assertTrue(panel.getSwitchPanel().isVisible(), "ODT has a physical switch");
			//-- ODT cannot halt a running CPU at all - it is in the CPU. The button stays enabled
			//-- so it can say which switch to move, which is the Pascal's rule.
			assertTrue(panel.getHaltButton().isEnabled());

			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.HALT);
			Edt.run(panel::updateDisplay);
			assertTrue(panel.getSingleStepButton().isEnabled(), "halted, so P steps one instruction");

			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.RUN);
			Edt.run(panel::updateDisplay);
			assertFalse(panel.getSingleStepButton().isEnabled(), "enabled, so P continues instead");
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aRunningMachineOffersOnlyHalt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			ctx.getMachineState().running();
			Edt.run(panel::updateDisplay);

			assertTrue(panel.getHaltButton().isEnabled());
			assertFalse(panel.getSingleStepButton().isEnabled());
			assertEquals("Machine: running", panel.getStateText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void aStopShowsWhereTheMachineStopped(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 01234));
			Edt.run(panel::updateDisplay);

			assertEquals("001234", panel.getCurrentPcField().getText());
			assertEquals("Machine: stopped at 001234", panel.getStateText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * The window that is not open still hears about the stop.
	 *
	 * <p>This is what {@link MachineState} buys: in the Pascal the PC reaches the disassembler
	 * because the execution window calls it by name, so a stop while that window was shut told
	 * nobody anything.</p>
	 */
	@Test
	void theMachineStateIsFollowedWithNoWindowOpenAtAll(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			assertEquals(MachineState.ExecutionState.UNKNOWN, ctx.getMachineState().getState());
			ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 0500));
			assertEquals(MachineState.ExecutionState.STOPPED, ctx.getMachineState().getState());
			assertEquals(0500, ctx.getMachineState().getPc().val());

			//-- And a new connection forgets it, because it is a different machine.
			ctx.getConnectionManager().disconnect();
			assertEquals(MachineState.ExecutionState.UNKNOWN, ctx.getMachineState().getState());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * Continue says RUNNING once the console has taken the command - not before it is queued.
	 *
	 * <p>Recorded as a list of every state rather than read at the end, because the simulated
	 * machine may well halt again immediately: what matters is that RUNNING happened, not that
	 * it is still true by the time this looks.</p>
	 */
	@Test
	void continuingSaysRunningOnceTheConsoleHasTakenIt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.SIMH);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			List<MachineState.ExecutionState> seen = record(ctx);
			assertTrue(panel.getContinueButton().isEnabled());

			Edt.run(() -> panel.getContinueButton().doClick());
			until("the machine to be running", () -> seen.contains(MachineState.ExecutionState.RUNNING));
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * A console that refuses to start leaves the machine state alone.
	 *
	 * <p>The refusal here is the ordinary one: the operator moves the physical ENABLE/HALT
	 * switch back to HALT, and the window - which is told about connections and stops, not about
	 * switches - still has Continue enabled from when the switch was at ENABLE. ODT's {@code P}
	 * means "single step" with the machine halted, so the console refuses the continue.</p>
	 *
	 * <p>Before this was fixed the window set RUNNING on the event thread <i>before</i> queueing
	 * the job, so the refusal left MachineState RUNNING for good: Reset, Continue, Single step
	 * and Set/show all disable on {@code running}, and the only way out was pressing Halt
	 * against a machine that had never started.</p>
	 */
	@Test
	void aRefusedContinueLeavesTheMachineStateAlone(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.ODT_16);
		try {
			List<String> failures = new CopyOnWriteArrayList<>();
			ctx.setFailureHandler((message, x) -> failures.add(message));
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			List<MachineState.ExecutionState> seen = record(ctx);

			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.RUN);
			Edt.run(panel::updateDisplay);
			assertTrue(panel.getContinueButton().isEnabled(), "at ENABLE, P continues");

			//-- The switch moves back. Nothing tells the window, which is the whole point.
			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.HALT);
			Edt.run(() -> panel.getContinueButton().doClick());

			until("the console to refuse", () -> !failures.isEmpty());
			assertEquals("Continue failed", failures.get(0));
			assertFalse(seen.contains(MachineState.ExecutionState.RUNNING),
				"nothing started, so nothing is running");
			assertEquals(MachineState.ExecutionState.UNKNOWN, ctx.getMachineState().getState());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	@Test
	void renderToAFileForLookingAt(@TempDir Path dir) throws Exception {
		AppContext ctx = connected(dir, ConsoleProtocol.ODT_16);
		try {
			ExecutionPanel panel = Edt.call(() -> new ExecutionPanel(ctx));
			Edt.run(panel::attach);
			ctx.getConnectionManager().getConsole().setRunMode(ConsoleRunMode.HALT);
			ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 01000));
			Edt.run(panel::updateDisplay);
			Path file = UiRenderer.renderToFile(panel, 700, 260,
				Path.of("target", "ui-render", "execution-panel.png"));
			assertTrue(Files.size(file) > 0);
		} finally {
			ctx.getConnectionManager().close();
		}
	}
}
