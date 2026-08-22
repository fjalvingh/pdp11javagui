package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.mmu.Pdp11Mmu;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.ProgressMonitor;

import java.util.List;

/**
 * What every console dialect shares: the receive path, the answer collection, the prompt check,
 * and the execution-stop event.
 *
 * <p>Ported from the concrete half of {@code TConsoleGeneric} ({@code ConsoleGenericU.pas}).
 * Three of its members do not survive the port, and that is the point of PLAN.md §1:</p>
 *
 * <ul>
 * <li>{@code BeginCriticalSection}/{@code EndCriticalSection} and the nesting counter
 *     ({@code :392-408}) are gone. The command executor is the critical section.</li>
 * <li>The 100 ms {@code MonitorTimer} ({@code :358-361, 511-531}) is gone. A stop event is
 *     posted onto the command executor the moment the decoder confirms one, and serialization
 *     gives the ordering the timer was there to arrange - a handler may issue console commands
 *     immediately, and they cannot interleave with anything.</li>
 * <li>The {@code Application.ProcessMessages} between decoded phrases ({@code :424}) is gone.
 *     It was a UI-responsiveness hack for a chunk that yields many phrases; with a reader
 *     thread the UI is responsive because it is a different thread, and preserving it would
 *     mean deliberately making the decoder reentrant.</li>
 * </ul>
 *
 * <h2>Which thread</h2>
 *
 * <p>{@link #onSerialReceive} runs on the reader thread and is the only thing that touches the
 * scanner - except {@link #resetScanner()}, which a command may call, and which takes the same
 * lock. Everything else here runs on the command thread.</p>
 */
public abstract class AbstractConsole implements Console, SerialReceiver {
	private final Logger m_logger;

	private final AnswerCollector m_answers = new AnswerCollector();

	/** Held while decoding, and by anything that clears the scanner out from under it. */
	private final Object m_decodeLock = new Object();

	/** Volatile: set on the command thread, read by the reader thread's decoder. */
	private volatile ConsoleConnection m_connection;

	private Pdp11Mmu m_mmu;

	/** How long a command has to be answered. Set by the subclass; dialects differ wildly. */
	private long m_commandTimeoutMillis = 1000;

	private ConsoleRunMode m_runMode = ConsoleRunMode.UNKNOWN;

	private volatile ExecutionStopListener m_stopListener;

	/**
	 * Where the machine stopped, once the decoder is sure. Written on the reader thread, read
	 * on the command thread - {@code HaltCpu} picks it up after its own prompt arrives.
	 */
	private volatile Address m_executionStopPc;

	private volatile boolean m_executionStopDetected;

	protected AbstractConsole(Logger logger) {
		m_logger = logger;
	}

	/** The scanner this dialect decodes with. Constructed by the subclass. */
	protected abstract ConsoleScanner<?> getScanner();

	/**
	 * Pull one complete phrase out of the scanner's buffer, publishing it if there is one.
	 *
	 * <p>Ported from {@code DecodeNextAnswerPhrase}. Called repeatedly until it returns false,
	 * which means "nothing complete in the buffer yet".</p>
	 *
	 * @return whether a phrase was recognised, so the caller should try again
	 */
	protected abstract boolean decodeNextAnswerPhrase();

	// -------------------------------------------------------------------------------------
	// Connection
	// -------------------------------------------------------------------------------------

	/**
	 * Attach to a connection and get the console talking. Subclasses override to do the
	 * dialect's own handshake, then call this.
	 */
	public void init(ConsoleConnection connection) throws ConsoleException {
		m_connection = connection;
		clearExecutionStop();
	}

	public ConsoleConnection getConnection() {
		return m_connection;
	}

	protected Logger getLogger() {
		return m_logger;
	}

	public AnswerCollector getAnswers() {
		return m_answers;
	}

	public Pdp11Mmu getMmu() {
		return m_mmu;
	}

	protected void setMmu(Pdp11Mmu mmu) {
		m_mmu = mmu;
	}

	public long getCommandTimeoutMillis() {
		return m_commandTimeoutMillis;
	}

	protected void setCommandTimeoutMillis(long commandTimeoutMillis) {
		m_commandTimeoutMillis = commandTimeoutMillis;
	}

	@Override
	public ConsoleRunMode getRunMode() {
		return m_runMode;
	}

	/** Where the ENABLE/HALT switch is. Only meaningful with {@link ConsoleFeature#SWITCH_ENABLE_OR_HALT}. */
	@Override
	public void setRunMode(ConsoleRunMode runMode) {
		m_runMode = runMode;
	}

	@Override
	public void setExecutionStopListener(ExecutionStopListener listener) {
		m_stopListener = listener;
	}

	@Override
	public void clearState() {
		clearExecutionStop();
	}

	// -------------------------------------------------------------------------------------
	// The receive path - reader thread
	// -------------------------------------------------------------------------------------

	@Override
	public final void onSerialReceive(String data) {
		synchronized(m_decodeLock) {
			ConsoleScanner<?> scanner = getScanner();
			scanner.moreInput(data);
			if(m_logger.isEnabled(LogChannel.PROTOCOL))
				m_logger.log(LogChannel.PROTOCOL,
					"received \"" + NoConsolePromptException.printable(data) + "\"");
			//-- No ProcessMessages in here, unlike :424 - see the class comment.
			while(decodeNextAnswerPhrase()) {
				//-- Keep going while phrases keep coming out.
			}
		}
	}

	@Override
	public void onDisconnected(Throwable cause) {
		//-- Wake anything waiting for an answer that can no longer arrive.
		m_answers.close();
	}

	/** Publish a decoded phrase. Called from the decoder, on the reader thread. */
	protected void publish(AnswerPhrase phrase) {
		m_logger.log(LogChannel.PROTOCOL, phrase.asText());
		m_answers.publish(phrase);
	}

	/**
	 * Throw away unparsed input and everything decoded so far.
	 *
	 * <p>Takes the decode lock, because the reader thread may be halfway through a phrase.
	 * {@code Resync} does this ({@code ConsolePDP11SimHU.pas:578-579}).</p>
	 */
	protected void resetScanner() {
		synchronized(m_decodeLock) {
			getScanner().clear();
			m_answers.clear();
		}
	}

	// -------------------------------------------------------------------------------------
	// Talking to the machine - command thread
	// -------------------------------------------------------------------------------------

	/** Send text. The caller supplies its own line terminator, exactly as the Pascal does. */
	protected void writeToPdp(String text) throws ConsoleException {
		ConsoleConnection c = m_connection;
		if(c == null)
			throw new ConsoleException("This console is not connected");
		c.write(text);
	}

	/** Forget everything said so far. Done immediately before sending a command. */
	protected void clearAnswers() {
		m_answers.clear();
	}

	protected <T extends AnswerPhrase> T waitForAnswer(Class<T> type, long timeoutMillis) {
		return m_answers.waitFor(type, timeoutMillis);
	}

	/**
	 * Make sure the prompt came back, so whatever was sent is known to have been dealt with.
	 *
	 * <p>Ported from {@code CheckPrompt} ({@code ConsoleGenericU.pas:474-508}), minus the modal
	 * dialog it showed from inside the protocol layer.</p>
	 */
	protected void checkPrompt(String errinfo) throws NoConsolePromptException {
		checkPromptAfter(0, errinfo);
	}

	/**
	 * Make sure a prompt came back <i>after</i> a known point in the conversation.
	 *
	 * <p>The Pascal has only the position-free form, and on SimH that is not enough: its prompt
	 * for the previous command can still be in flight when this one is sent, so "a prompt
	 * arrived" is not the same as "this command finished". See {@link AnswerCollector}.</p>
	 */
	protected void checkPromptAfter(int fromIndex, String errinfo) throws NoConsolePromptException {
		if(m_answers.waitFor(AnswerPhrase.Prompt.class, fromIndex, m_commandTimeoutMillis) != null)
			return;
		String unconsumed;
		synchronized(m_decodeLock) {
			unconsumed = getScanner().getInput();
		}
		NoConsolePromptException x = new NoConsolePromptException("No console prompt: " + errinfo + "!",
			unconsumed, m_answers.snapshot());
		x.logDiagnostics(m_logger);
		throw x;
	}

	// -------------------------------------------------------------------------------------
	// Execution stop
	// -------------------------------------------------------------------------------------

	/** Where the machine last stopped, or {@code null}. */
	public Address getExecutionStopPc() {
		return m_executionStopPc;
	}

	public boolean isExecutionStopDetected() {
		return m_executionStopDetected;
	}

	protected void clearExecutionStop() {
		m_executionStopPc = null;
		m_executionStopDetected = false;
	}

	/**
	 * The decoder is sure the machine stopped, and where.
	 *
	 * <p>Called on the reader thread. The listener is not called from here: it is posted to the
	 * command thread, so it lands <i>after</i> whatever command is running and may itself issue
	 * console commands - which is precisely what the Pascal's monitor timer was arranging with
	 * its critical-section guard ({@code ConsoleGenericU.pas:511-531}).</p>
	 */
	protected void signalExecutionStop(Address pc) {
		m_executionStopPc = pc;
		m_executionStopDetected = true;
		ConsoleConnection c = m_connection;
		if(c == null)
			return;
		//-- The address is captured here, not read back from the field when the task runs.
		//-- Anything that clears the field in between - another prompt with no halt in front of
		//-- it will do it - would otherwise lose the event entirely.
		c.execute(() -> {
			clearExecutionStop();
			ExecutionStopListener l = m_stopListener;
			if(l != null)
				l.onExecutionStop(this, pc);
		});
	}

	// -------------------------------------------------------------------------------------
	// Bulk operations
	// -------------------------------------------------------------------------------------

	/**
	 * Write a whole group, one cell at a time.
	 *
	 * <p>Ported from {@code TConsoleGeneric.Deposit(mcg, optimize, abortable)}
	 * ({@code ConsoleGenericU.pas:534-556}). Dialects that can batch overriding this is the
	 * exception, not the rule: a deposit has no answer to batch.</p>
	 *
	 * <p>Two deliberate differences. Cancelling <i>stops</i> rather than unwinds, as the Pascal
	 * does - half a group deposited is a real state of the machine, not an error to roll back.
	 * And a cell whose edit value was never set is skipped: the Pascal sends its
	 * {@code MEMORYCELL_ILLEGALVAL} sentinel to the machine as a value, which deposits
	 * {@code 177777} into whatever it names. That is the sentinel bug PLAN.md §2 predicted, and
	 * here it cannot be written at all.</p>
	 */
	@Override
	public void deposit(MemoryCellGroup g, boolean optimize, ProgressMonitor pm) throws ConsoleException {
		List<MemoryCell> cells = List.copyOf(g.getCells());
		MemoryCellGroups owner = g.getOwner();
		pm.begin("Depositing ...", cells.size());
		try {
			for(MemoryCell mc : cells) {
				pm.step(1);
				if(pm.isCancelled())
					break;
				if(!mc.getEditValue().isKnown())
					continue;
				if(optimize && mc.getEditValue().equals(mc.getPdpValue()))
					continue;
				deposit(mc.getAddr(), mc.getEditValue().word());
				mc.setDeposited();
				//-- Update the same cell in the other groups, which is what fires their listeners.
				if(owner != null)
					owner.syncMemoryCells(mc);
			}
		} finally {
			pm.done();
		}
	}
}
