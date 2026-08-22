package to.etc.pdp11.ui.exec;

import net.miginfocom.swing.MigLayout;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionManager;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.ConsoleFeature;
import to.etc.pdp11.core.console.ConsoleRunMode;
import to.etc.pdp11.core.macro11.Macro11;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.macro11.AssemblerModel;
import to.etc.pdp11.ui.window.WindowType;
import to.etc.pdp11.ui.MachineState;
import to.etc.pdp11.ui.UiColors;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import java.awt.Font;
import java.util.EnumSet;

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
 * <p>Which buttons work depends on two things at once: what the console can do
 * ({@link ConsoleFeature}) and what the machine is doing. An ODT console cannot single-step
 * without a HALT switch; SimH cannot reset without also starting; an 11/44 in RUN reports a
 * different feature set than the same console in HALT. Getting this wrong does not produce an
 * error message, it produces a button that appears to do nothing, so the table below is ported
 * case by case from {@code UpdateDisplay} ({@code :281-380}).</p>
 *
 * <p>One rule of the original is kept exactly and is worth stating: <b>Halt is always
 * enabled</b>. A console that cannot halt still knows how the operator can - by moving a
 * switch - and saying so is more use than a dead button.</p>
 */
public final class ExecutionPanel extends JPanel {
	/** R7's offset within the I/O page, which is where a console deposits a new PC. */
	private static final int REG_R7 = 017707;

	private final AppContext m_context;

	private final JTextField m_startPc = new JTextField(8);

	private final JTextField m_currentPc = new JTextField(8);

	private final JButton m_reset = new JButton("Reset");

	private final JButton m_resetAndStart = new JButton("Reset and start");

	private final JButton m_continue = new JButton("Continue");

	private final JButton m_halt = new JButton("Halt");

	private final JButton m_singleStep = new JButton("Single step");

	private final JButton m_setPc = new JButton("Set/show");

	private final JButton m_newProgram = new JButton("New program");

	private final JRadioButton m_run = new JRadioButton("RUN/ENABLE");

	private final JRadioButton m_haltSwitch = new JRadioButton("HALT");

	private final JPanel m_switchPanel = new JPanel(new MigLayout("insets 4", "[]12[]20[grow]", "[]"));

	private final JLabel m_switchInfo = new JLabel("<html>Keep this in step with the RUN/HALT switch on the"
		+ " physical machine.<br>It is what enables Reset, Start, Continue and Single step.</html>");

	private final JLabel m_state = new JLabel();

	public ExecutionPanel(AppContext context) {
		super(new MigLayout("fill, insets 8", "[][]12[][]12[grow]", "[][][][][][grow]"));
		m_context = context;

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

		m_newProgram.setToolTipText("Assemble the program in the Assembler window, load it into the"
			+ " machine, reset, and set the PC to where it starts");
		add(m_newProgram, "cell 2 3, spanx 2, growx, wrap");

		ButtonGroup group = new ButtonGroup();
		group.add(m_run);
		group.add(m_haltSwitch);
		m_switchPanel.setBorder(new TitledBorder("Physical RUN/HALT switch"));
		m_switchPanel.add(m_run);
		m_switchPanel.add(m_haltSwitch);
		m_switchPanel.add(m_switchInfo, "growx");
		add(m_switchPanel, "cell 0 4, spanx, growx, wrap");

		m_state.setForeground(UiColors.SECONDARY_TEXT);
		add(m_state, "cell 0 5, spanx, growx");

		m_reset.addActionListener(e -> doResetAndSetPc());
		m_resetAndStart.addActionListener(e -> doResetAndStart());
		m_continue.addActionListener(e -> doContinue());
		m_halt.addActionListener(e -> doHalt());
		m_singleStep.addActionListener(e -> doSingleStep());
		m_setPc.addActionListener(e -> doSetPc());
		m_newProgram.addActionListener(e -> doNewProgram());
		m_run.addActionListener(e -> setRunMode(ConsoleRunMode.RUN));
		m_haltSwitch.addActionListener(e -> setRunMode(ConsoleRunMode.HALT));

		updateDisplay();
	}

	// -------------------------------------------------------------------------------------
	// What is showing
	// -------------------------------------------------------------------------------------

	/** Everything on this panel that depends on the console or the machine. On the EDT. */
	public void updateDisplay() {
		Console console = m_context.getConnectionManager().getConsole();
		EnumSet<ConsoleFeature> features = console == null ? EnumSet.noneOf(ConsoleFeature.class) : console.features();
		MachineState.ExecutionState state = m_context.getMachineState().getState();
		boolean connected = console != null;
		boolean running = state == MachineState.ExecutionState.RUNNING;

		Address pc = m_context.getMachineState().getPc();
		if(pc != null && !m_currentPc.isFocusOwner())
			m_currentPc.setText(pc.toOctal());
		//-- A loaded program says where it starts, and this is the window that starts things.
		//-- Not while the field has focus: the user is mid-way through typing something else.
		Address startPc = m_context.getMachineState().getStartPc();
		if(startPc != null && !m_startPc.isFocusOwner())
			m_startPc.setText(startPc.toOctal());

		//-- Only what the console can do, and then only what makes sense where the machine is.
		m_reset.setEnabled(connected && !running && features.contains(ConsoleFeature.ACTION_RESET_MACHINE));
		m_resetAndStart.setEnabled(connected && !running
			&& features.contains(ConsoleFeature.ACTION_RESET_AND_START_CPU));
		m_continue.setEnabled(connected && !running && features.contains(ConsoleFeature.ACTION_CONTINUE_CPU));
		m_singleStep.setEnabled(connected && !running && features.contains(ConsoleFeature.ACTION_SINGLE_STEP));
		//-- Always available while connected, even without ACTION_HALT_CPU: a console that cannot
		//-- halt can still say which switch the operator should move ({@code :412-430}).
		m_halt.setEnabled(connected);
		//-- SimH refuses to deposit into a live PC, and refuses it silently, so a Set/show while
		//-- the CPU runs produces a confusing nothing rather than an error. The Pascal learned
		//-- this the same way ({@code :382-385}).
		m_setPc.setEnabled(connected && !running);
		//-- Compile, load and reset. Needs a machine to load into, a program to load, and the
		//-- external assembler to make one with.
		m_newProgram.setEnabled(connected && !running && m_context.getAssembler().canAssemble()
			&& Macro11.isAvailable());

		boolean hasSwitch = features.contains(ConsoleFeature.SWITCH_ENABLE_OR_HALT);
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

		m_state.setText(connected
			? "Machine: " + switch(state) {
				case UNKNOWN -> "state unknown";
				case STOPPED -> "stopped" + (pc == null ? "" : " at " + pc.toOctal());
				case RUNNING -> "running";
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

	// -------------------------------------------------------------------------------------
	// The commands
	// -------------------------------------------------------------------------------------

	/**
	 * Reset the machine and put the PC where the Start PC field says.
	 *
	 * <p>Ported from {@code doResetMachineAndSetPC} ({@code :385-414}). The deposit happens
	 * <i>after</i> the reset and happens whether or not the console claims
	 * {@link ConsoleFeature#RESET_CPU_SETS_PC} - a reset that does set the PC has just set it to
	 * the same value, and one that does not needs this.</p>
	 */
	private void doResetAndSetPc() {
		Address start = parse(m_startPc, "start PC");
		if(start == null || !checkRunMode())
			return;
		m_context.onConsole("Reset", console -> {
			if(console.features().contains(ConsoleFeature.ACTION_RESET_MACHINE))
				console.resetMachine(start);
			depositPc(console, start);
			m_context.getMachineState().stopped(start);
		});
	}

	private void doResetAndStart() {
		Address start = parse(m_startPc, "start PC");
		if(start == null || !checkRunMode())
			return;
		m_context.getMachineState().running();
		m_context.onConsole("Reset and start", console -> console.resetAndStart(start));
	}

	private void doContinue() {
		if(!checkRunMode())
			return;
		m_context.getMachineState().running();
		m_context.onConsole("Continue", Console::continueCpu);
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
		if(!console.features().contains(ConsoleFeature.ACTION_HALT_CPU)) {
			m_context.reportFailure(haltAdvice(console), null);
			return;
		}
		m_context.onConsole("Halt", c -> {
			Address pc = c.haltCpu();
			m_context.getMachineState().stopped(pc);
		});
	}

	private static String haltAdvice(Console console) {
		if(!console.features().contains(ConsoleFeature.SWITCH_ENABLE_OR_HALT))
			return "This console cannot halt the CPU; stop it from the machine's own console panel";
		return switch(console.getRunMode()) {
			case HALT -> "The RUN/HALT switch is already at HALT, so the processor should be stopped";
			case RUN -> "Halt the CPU by moving the physical RUN/HALT switch to HALT";
			case UNKNOWN -> "Set the RUN/HALT switch to match the machine before halting";
		};
	}

	/**
	 * Assemble the current program, load it into the machine, reset, and set the PC.
	 *
	 * <p>Ported from {@code NewPgmButtonClick} ({@code FormExecuteU.pas:166-188}), which does it
	 * by reaching across and pressing another window's buttons -
	 * {@code FormMain.FormMacro11Source.Show}, then {@code CompileButtonClick(nil)}, then
	 * {@code FormMain.FormMacro11Listing.DepositAllButtonClick(nil)}. That needs both assembler
	 * windows to exist and to be showing. Here the program is {@link AppContext#getAssembler()},
	 * which is state rather than a window, so this works with no assembler window open at all -
	 * though one is opened anyway, because watching a program assemble is the point of pressing
	 * this.</p>
	 *
	 * <p>The three steps are chained through callbacks rather than run in sequence: each of them
	 * happens on a different thread from this one - the assembler on a worker, the deposit on the
	 * command thread - and waiting for either from the event thread would freeze the window at
	 * best and deadlock it at worst.</p>
	 */
	private void doNewProgram() {
		Address start = parse(m_startPc, "start PC");
		if(start == null || !checkRunMode())
			return;
		AssemblerModel assembler = m_context.getAssembler();
		if(!assembler.canAssemble()) {
			m_context.reportFailure("Open a MACRO-11 source in the Assembler window first", null);
			m_context.getWindowManager().open(WindowType.ASSEMBLER);
			return;
		}
		//-- Raised before rather than after: the assembler is where an error will be shown.
		m_context.getWindowManager().open(WindowType.ASSEMBLER);
		assembler.assemble(outcome -> {
			if(!outcome.ok())
				return;                                      // the assembler window says why
			assembler.deposit(SwingUtilities.getWindowAncestor(this), this::doResetAndSetPc);
		});
	}

	/** Write the Current PC field into the machine. {@code SetPcButtonClick} ({@code :237-247}). */
	private void doSetPc() {
		Address pc = parse(m_currentPc, "current PC");
		if(pc == null)
			return;
		m_context.onConsole("Setting the PC", console -> {
			depositPc(console, pc);
			m_context.getMachineState().stopped(pc);
		});
	}

	/**
	 * Deposit a virtual PC into R7.
	 *
	 * <p>R7 lives at the top of the I/O page - {@code 0177707} on a 16-bit machine,
	 * {@code 017777707} on a 22-bit one - which is why the address is built from the console's
	 * own width rather than written down. On the command thread.
	 */
	private static void depositPc(Console console, Address pc) throws ConsoleException {
		MemoryAddressType type = console.physicalAddressType();
		console.deposit(Address.of(type, type.getIopageBase() + REG_R7), (int) (pc.val() & 0xFFFF));
	}

	private void setRunMode(ConsoleRunMode mode) {
		Console console = m_context.getConnectionManager().getConsole();
		if(console != null)
			console.setRunMode(mode);
		//-- And the simulated machine, which has no switch to look at either.
		m_context.getConnectionManager().setSimulatedRunMode(mode == ConsoleRunMode.RUN);
		updateDisplay();
	}

	/**
	 * Refuse to act while the operator has not said where the switch is.
	 *
	 * <p>{@code CheckRunMode} ({@code :265-275}) raises an exception here, which is right: on a
	 * machine with a physical switch, a console told the wrong position will issue commands the
	 * machine ignores, and the user is left with a UI that appears broken.</p>
	 */
	private boolean checkRunMode() {
		Console console = m_context.getConnectionManager().getConsole();
		if(console == null || !console.features().contains(ConsoleFeature.SWITCH_ENABLE_OR_HALT))
			return true;
		if(console.getRunMode() != ConsoleRunMode.UNKNOWN)
			return true;
		m_context.reportFailure("Set RUN or HALT to match the switch on the machine's console first", null);
		return false;
	}

	/** A virtual address from a text field, complaining rather than throwing if it is not one. */
	private Address parse(JTextField field, String what) {
		try {
			return Address.parseOctal(field.getText().trim(), MemoryAddressType.VIRTUAL);
		} catch(RuntimeException x) {
			m_context.reportFailure("\"" + field.getText().trim() + "\" is not an octal " + what, null);
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
