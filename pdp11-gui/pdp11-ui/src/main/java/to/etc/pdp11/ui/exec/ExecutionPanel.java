package to.etc.pdp11.ui.exec;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.Capabilities;
import to.etc.pdp11.core.conn.Capability;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleRunMode;
import to.etc.pdp11.ui.FieldStatus;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.UiColors;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;
import java.awt.Font;

/**
 * Run control: reset, start, continue, halt, single step, and where the PC is.
 *
 * <p>Ported from {@code TFormExecute} ({@code FormExecuteU.pas}). What is here is its buttons
 * and their enablement rules; what is <b>not</b> here is the five windows it reaches into on
 * every stop - the disassembler, the assembler listing, the memory cell bus and the 11/70
 * panel, named one by one in {@code SetAndShowPc} ({@code :198-235}). Those became
 * {@link MachineState}, which anything interested watches and nothing has to be told about.</p>
 *
 * <h2>Enablement is the substance, not decoration</h2>
 *
 * <p>Which buttons work depends on two things at once: what can be done with this machine over
 * this console ({@link Capabilities}) and what the machine is doing. An ODT console cannot single-step
 * without a HALT switch; SimH cannot reset without also starting; an 11/44 in RUN reports a
 * different feature set than the same console in HALT. Getting this wrong does not produce an
 * error message, it produces a button that appears to do nothing, so the table below is ported
 * case by case from {@code UpdateDisplay} ({@code :281-380}).</p>
 *
 * <p>Halt is enabled wherever this window can do something about a running machine: halt it
 * through the console, or - for a console that cannot, but has a RUN/HALT switch it knows
 * about, as ODT does - say which way to move the switch. The original enabled it always. That
 * is wrong on a boot ROM console (M9312, M9301): it has neither, the only advice it could give
 * is "use the front panel", and the button looked as if it would stop the program.</p>
 *
 * <p>There is no "New program" here any more. Compile, load, then start or set the PC is three
 * quarters assembler, and is the Assembler window's Compile and start and Compile and set PC.</p>
 *
 * <h2>A machine this window cannot stop does not lock it</h2>
 *
 * <p>On such a console a started program is out of sight. It can halt, be stopped and rebooted
 * from the front panel, or jump back into the console emulator, and only some of those are seen
 * here. So "running" there is a guess, and not a reason to disable Reset and start: disabling it
 * left a disconnect and reconnect as the only way to start anything again, even with the console
 * emulator long back at its prompt. If the program is in fact still running, the start fails
 * with no prompt and says so, which is no worse than pressing it on a machine that is not
 * listening at all.</p>
 */
public final class ExecutionPanel extends JPanel {
	private final AppContext m_context;

	private final RunActions m_runActions;

	private final JTextField m_startPc = new JTextField(8);

	private final JTextField m_currentPc = new JTextField(8);

	/**
	 * Not "Reset": it also deposits the Start PC field into R7, whatever the console's own reset
	 * does, so a button labelled with half of what it does was silently writing a register.
	 */
	private final JButton m_reset = new JButton("Reset and set PC");

	private final JButton m_resetAndStart = new JButton("Reset and start");

	private final JButton m_continue = new JButton("Continue");

	private final JButton m_halt = new JButton("Halt");

	private final JButton m_singleStep = new JButton("Single step");

	/**
	 * Not "Set/show": it only ever sets. The "show" half meant the disassembler jumping to the
	 * new PC through {@code MachineState}, which happens in a different window and is not
	 * something this button can be said to do.
	 */
	private final JButton m_setPc = new JButton("Set PC");

	private final JRadioButton m_run = new JRadioButton("RUN/ENABLE");

	private final JRadioButton m_haltSwitch = new JRadioButton("HALT");

	private final JPanel m_switchPanel = new JPanel(new MigLayout("insets 4", "[]12[]20[grow]", "[]"));

	private final JLabel m_switchInfo = new JLabel("<html>Keep this in step with the RUN/HALT switch on the"
		+ " physical machine.<br>It is what enables Reset, Start, Continue and Single step.</html>");

	private final JLabel m_state = new JLabel();

	/** The status line, and where a mistyped PC is reported. See {@link FieldStatus}. */
	private final FieldStatus m_status = new FieldStatus(m_state, UiColors.SECONDARY_TEXT);

	public ExecutionPanel(AppContext context) {
		super(new MigLayout("fill, insets 8", "[][]12[][]12[grow]", "[][][][][][grow]"));
		m_context = context;
		m_runActions = new RunActions(context);

		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, m_startPc.getFont().getSize());
		m_startPc.setFont(mono);
		m_currentPc.setFont(mono);
		m_startPc.setText("000000");
		m_currentPc.setText("000000");

		add(new JLabel("Start PC:"));
		add(m_startPc);
		add(m_reset);
		add(m_resetAndStart, "wrap");

		add(new JLabel("Current PC:"));
		add(m_currentPc);
		add(m_setPc);
		add(m_singleStep, "wrap");

		add(m_continue, "cell 2 2");
		add(m_halt, "cell 3 2, wrap");

		ButtonGroup group = new ButtonGroup();
		group.add(m_run);
		group.add(m_haltSwitch);
		m_switchPanel.setBorder(new TitledBorder("Physical RUN/HALT switch"));
		m_switchPanel.add(m_run);
		m_switchPanel.add(m_haltSwitch);
		m_switchPanel.add(m_switchInfo, "growx");
		add(m_switchPanel, "cell 0 4, spanx, growx, wrap");

		add(m_state, "cell 0 5, spanx, growx");

		m_reset.addActionListener(e -> doResetAndSetPc());
		m_resetAndStart.addActionListener(e -> doResetAndStart());
		m_continue.addActionListener(e -> doContinue());
		m_halt.addActionListener(e -> doHalt());
		m_singleStep.addActionListener(e -> doSingleStep());
		m_setPc.addActionListener(e -> doSetPc());
		m_run.addActionListener(e -> setRunMode(ConsoleRunMode.RUN));
		m_haltSwitch.addActionListener(e -> setRunMode(ConsoleRunMode.HALT));

		//-- Enter in an address field acts, as it does in the Memory, Disassembler, Dumper,
		//-- Memory Test and Bitfields windows. These two were the holdouts: same widget, same
		//-- shape, and the only way to act on what had been typed was to find the button.
		m_startPc.addActionListener(e -> publishStartPc());
		m_startPc.setToolTipText("Where a program starts. Enter publishes it to the other windows;"
			+ " Reset and start uses it.");
		m_currentPc.addActionListener(e -> doSetPc());
		m_currentPc.setToolTipText("The machine's PC. Enter writes it into R7, same as Set PC.");

		updateDisplay();
	}

	// -------------------------------------------------------------------------------------
	// What is showing
	// -------------------------------------------------------------------------------------

	/** Everything on this panel that depends on the console or the machine. On the EDT. */
	public void updateDisplay() {
		Console console = m_context.getConnectionManager().getConsole();
		Capabilities can = m_context.getConnectionManager().capabilities();
		MachineState.ExecutionState state = m_context.getMachineState().getState();
		boolean connected = console != null && can != Capabilities.NONE;
		boolean running = state == MachineState.ExecutionState.RUNNING;
		//-- Whether this window can do anything about a running machine; see the class comment.
		boolean canStop = can.canStopProgram();

		Address pc = m_context.getMachineState().getPc();
		if(pc != null && !m_currentPc.isFocusOwner())
			m_currentPc.setText(pc.toOctal());
		//-- A loaded program says where it starts, and this is the window that starts things.
		//-- Not while the field has focus: the user is mid-way through typing something else.
		Address startPc = m_context.getMachineState().getStartPc();
		if(startPc != null && !m_startPc.isFocusOwner())
			m_startPc.setText(startPc.toOctal());

		//-- Only what the console can do, and then only what makes sense where the machine is.
		m_reset.setEnabled(connected && !running && can.can(Capability.RESET) && can.can(Capability.SET_PC));
		m_resetAndStart.setEnabled(connected && !(running && canStop) && can.can(Capability.START));
		m_continue.setEnabled(connected && !running && can.can(Capability.CONTINUE));
		m_singleStep.setEnabled(connected && !running && can.can(Capability.SINGLE_STEP));
		//-- Also without ACTION_HALT_CPU when there is a switch: the console can still say which
		//-- way the operator should move it ({@code :412-430}).
		m_halt.setEnabled(connected && canStop);
		//-- SimH refuses to deposit into a live PC, and refuses it silently, so a Set/show while
		//-- the CPU runs produces a confusing nothing rather than an error. The Pascal learned
		//-- this the same way ({@code :382-385}).
		//-- And a boot ROM console cannot set it at all: the registers are its own.
		m_setPc.setEnabled(connected && !running && can.can(Capability.SET_PC));

		boolean hasSwitch = can.can(Capability.RUN_HALT_SWITCH);
		m_switchPanel.setVisible(hasSwitch);
		if(hasSwitch) {
			ConsoleRunMode mode = console.getRunMode();
			m_run.setSelected(mode == ConsoleRunMode.RUN);
			m_haltSwitch.setSelected(mode == ConsoleRunMode.HALT);
			//-- Red until the operator says where the switch is: until then the console does not
			//-- know what it is allowed to do, and neither do we.
			m_switchInfo.setForeground(mode == ConsoleRunMode.UNKNOWN
				? UiColors.ERROR_TEXT : UiColors.SECONDARY_TEXT);
		}

		m_status.setText(connected
			? "Machine: " + switch(state) {
				case UNKNOWN -> "state unknown";
				case STOPPED -> "stopped" + (pc == null ? "" : " at " + pc.toOctal());
				case RUNNING -> canStop ? "running"
					: "started; stop it from the front panel, this console cannot";
			}
			: "Not connected");
	}

	public String getStateText() {
		return m_state.getText();
	}

	public JTextField getStartPcField() {
		return m_startPc;
	}

	public JTextField getCurrentPcField() {
		return m_currentPc;
	}

	/** The RUN/HALT group, which is only shown for a console that has such a switch. */
	public JPanel getSwitchPanel() {
		return m_switchPanel;
	}

	public JButton getHaltButton() {
		return m_halt;
	}

	public JButton getSingleStepButton() {
		return m_singleStep;
	}

	public JButton getResetAndStartButton() {
		return m_resetAndStart;
	}

	public JButton getContinueButton() {
		return m_continue;
	}

	// -------------------------------------------------------------------------------------
	// The commands
	// -------------------------------------------------------------------------------------

	/** Reset the machine and put the PC where the Start PC field says. See {@link RunActions}. */
	private void doResetAndSetPc() {
		Address start = parse(m_startPc, "start PC");
		if(start != null)
			m_runActions.resetAndSetPc(start);
	}

	/** Reset the machine and start it running at the Start PC. See {@link RunActions#start}. */
	private void doResetAndStart() {
		Address start = parse(m_startPc, "start PC");
		if(start != null)
			m_runActions.start(start);
	}

	/** Carry on from where the machine stopped. RUNNING once the console has taken it, as above. */
	private void doContinue() {
		if(!checkRunMode())
			return;
		m_context.onConsole("Continue", console -> {
			console.continueCpu();
			m_context.getMachineState().running();
		});
	}

	private void doSingleStep() {
		if(!checkRunMode())
			return;
		//-- The new PC arrives as a stop event, which is what moves the display on.
		m_context.onConsole("Single step", Console::singleStep);
	}

	/**
	 * Stop the machine, or explain how to.
	 *
	 * <p>Ported from {@code doHalt} ({@code :406-430}). A console with no halt command is not a
	 * failure to report - it is a machine with a switch on the front of it, and what the user
	 * needs is to be told which way to turn it.</p>
	 */
	private void doHalt() {
		Console console = m_context.getConnectionManager().getConsole();
		if(console == null) {
			m_context.reportFailure("Not connected to a machine", null);
			return;
		}
		Capabilities can = m_context.getConnectionManager().capabilities();
		if(!can.can(Capability.HALT)) {
			//-- Only reachable with a switch: without one the button is disabled.
			if(!can.can(Capability.RUN_HALT_SWITCH))
				return;
			m_context.reportFailure(haltAdvice(console), null);
			return;
		}
		//-- Out of band first, where the console has such a thing: a command already in flight
		//-- against a running machine would otherwise hold this one up for its whole timeout
		//-- (FABLE-ISSUES #48). haltCpu below still does all the deciding.
		console.interruptRunningProgram();
		m_context.onConsole("Halt", c -> {
			Address pc = c.haltCpu();
			m_context.getMachineState().stopped(pc);
		});
	}

	private static String haltAdvice(Console console) {
		return switch(console.getRunMode()) {
			case HALT -> "The RUN/HALT switch is already at HALT, so the processor should be stopped";
			case RUN -> "Halt the CPU by moving the physical RUN/HALT switch to HALT";
			case UNKNOWN -> "Set the RUN/HALT switch to match the machine before halting";
		};
	}

	/**
	 * Publish what has been typed as the program's start address, without touching the machine.
	 *
	 * <p>What the field already <i>means</i> - {@link #updateDisplay} writes {@code MachineState}'s
	 * start PC into it whenever the user is not typing - so Enter simply completes the round
	 * trip. Deliberately not "Reset and set PC", which is the button beside it: resetting a
	 * machine is not something a keystroke in a text field should do.</p>
	 */
	private void publishStartPc() {
		Address start = parse(m_startPc, "start PC");
		if(start == null)
			return;
		m_context.getMachineState().setStartPc(start);
	}

	/** Write the Current PC field into the machine. {@code SetPcButtonClick} ({@code :237-247}). */
	private void doSetPc() {
		Address pc = parse(m_currentPc, "current PC");
		if(pc == null)
			return;
		m_runActions.setPc(pc);
	}

	private void setRunMode(ConsoleRunMode mode) {
		Console console = m_context.getConnectionManager().getConsole();
		if(console != null)
			console.setRunMode(mode);
		//-- And the simulated machine, which has no switch to look at either.
		m_context.getConnectionManager().setSimulatedRunMode(mode == ConsoleRunMode.RUN);
		updateDisplay();
	}

	private boolean checkRunMode() {
		return m_context.checkRunMode();
	}

	/** A virtual address from a text field, complaining rather than throwing if it is not one. */
	private Address parse(JTextField field, String what) {
		try {
			return Address.parseOctal(field.getText().trim(), MemoryAddressType.VIRTUAL);
		} catch(RuntimeException x) {
			m_status.error("\"" + field.getText().trim() + "\" is not an octal " + what);
			return null;
		}
	}

	// -------------------------------------------------------------------------------------
	// Following the machine
	// -------------------------------------------------------------------------------------

	private final MachineState.Listener m_machineListener = state -> updateDisplay();

	private final ConnectionManager.Listener m_connectionListener =
		(manager, state) -> AppContext.onUi(this::updateDisplay);

	/** Start following the machine. Called when the window is first shown. */
	public void attach() {
		//-- Remove first: showing an already-visible window runs this again, and a listener list
		//-- that grows one entry per raise is a leak that only shows up as a slow window.
		detach();
		m_context.getMachineState().addListener(m_machineListener);
		m_context.getConnectionManager().addListener(m_connectionListener);
		updateDisplay();
	}

	public void detach() {
		m_context.getMachineState().removeListener(m_machineListener);
		m_context.getConnectionManager().removeListener(m_connectionListener);
	}
}
