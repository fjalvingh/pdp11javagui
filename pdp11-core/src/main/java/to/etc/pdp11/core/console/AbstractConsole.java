package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.mmu.Pdp11Mmu;
import to.etc.pdp11.core.mmu.TranslationResult;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.ProgressMonitor;

import java.util.Comparator;
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

	/**
	 * A stop report waiting for the prompt that makes it safe to act on. Reader thread only,
	 * under {@link #m_decodeLock}. See {@link #takeHaltAwaitingPrompt}.
	 */
	private AnswerPhrase.Halt m_haltAwaitingPrompt;

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
		if(phrase instanceof AnswerPhrase.Halt halt)
			m_haltAwaitingPrompt = halt;
		m_logger.log(LogChannel.PROTOCOL, phrase.asText());
		m_answers.publish(phrase);
	}

	/**
	 * The stop report the prompt now being decoded completes, or {@code null}. Consumes it.
	 *
	 * <p>All three consoles fire the stop event from the <b>prompt</b> rather than from the halt
	 * line, because a handler goes straight on to issue console commands and can only do that
	 * once the machine is listening again. Which means each of them has to remember, across
	 * decode passes, that a halt was reported.</p>
	 *
	 * <p>They used to remember it by looking back in {@link AnswerCollector} - the last phrase,
	 * or two back on the 11/44, whose stop report is also an examine answer. That list is not
	 * theirs: {@code clearAnswers()} empties it from the command thread immediately before every
	 * command, and a command issued in the millisecond between the halt and its prompt left the
	 * prompt finding nothing, so the stop was never reported and the window stayed saying the
	 * machine was running (FABLE-ISSUES #46). Here it is the decoder's own state, on the reader
	 * thread under the decode lock, and nothing on the command thread can reach it.</p>
	 */
	protected final AnswerPhrase.Halt takeHaltAwaitingPrompt() {
		AnswerPhrase.Halt halt = m_haltAwaitingPrompt;
		m_haltAwaitingPrompt = null;
		return halt;
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
			//-- A resync throws the conversation away, and a halt nobody has been told about is
			//-- part of it. This is the only thing that drops one unreported.
			m_haltAwaitingPrompt = null;
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

	/**
	 * Forget everything said so far. Done immediately before sending a command.
	 *
	 * <p>Under the decode lock, so that a decode pass is either entirely before this or entirely
	 * after it - a phrase decoded from a chunk of input is not thrown away while its siblings
	 * survive. What this no longer decides is whether a stop event happens: see
	 * {@link #takeHaltAwaitingPrompt}.</p>
	 */
	protected void clearAnswers() {
		synchronized(m_decodeLock) {
			m_answers.clear();
		}
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
		NoConsolePromptException x = new NoConsolePromptException("No console prompt: " + errinfo + "!",
			getUnconsumedInput(), m_answers.snapshot());
		x.logDiagnostics(m_logger);
		throw x;
	}

	/**
	 * Whatever the scanner has not turned into a phrase yet - the diagnostic that goes into a
	 * {@link NoConsolePromptException}.
	 *
	 * <p>Under the decode lock, and that is the whole point of it existing. The scanner's buffer
	 * is a plain StringBuilder appended by the reader thread inside {@link #onSerialReceive}; a
	 * command thread calling {@code toString()} on it while an append is in flight reads a
	 * half-grown array and can come back with corrupt text or an
	 * {@link ArrayIndexOutOfBoundsException} - out of the code whose job is to <i>explain</i> a
	 * failure.</p>
	 */
	protected final String getUnconsumedInput() {
		synchronized(m_decodeLock) {
			return getScanner().getInput();
		}
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

	// -------------------------------------------------------------------------------------
	// Addresses
	// -------------------------------------------------------------------------------------

	/**
	 * The address as this console has to name it: physical, at its own width.
	 *
	 * <p>A virtual address goes through the MMU, and which of its two maps depends on what the
	 * caller is about to do with the result - the Pascal deposits through the <b>instruction</b>
	 * map on the reasoning that what gets written is nearly always code, and examines through
	 * the data map. A physical address at another width is rebased, which matters in the I/O
	 * page and nowhere else (see {@link Address#withWidth}).</p>
	 *
	 * <p>A console whose dialect needs something different - SimH passes special registers
	 * straight through, because it names them rather than addressing them - does its own.</p>
	 */
	protected final Address toPhysical(Address addr, boolean instructionSpace) throws ConsoleException {
		MemoryAddressType type = physicalAddressType();
		if(addr.type() == MemoryAddressType.VIRTUAL) {
			TranslationResult tr = instructionSpace
				? getMmu().translateInstruction(addr)
				: getMmu().translateData(addr);
			if(!tr.isValid())
				throw new ConsoleException("Cannot translate " + addr.toOctal() + ": " + tr.failure());
			return tr.address().withWidth(type);
		}
		if(addr.type() == type)
			return addr;
		if(addr.type().isConcretePhysical())
			return addr.withWidth(type);
		throw new ConsoleException("A " + name() + " cannot address " + addr);
	}

	// -------------------------------------------------------------------------------------
	// Bulk examine
	// -------------------------------------------------------------------------------------

	/**
	 * How long a block command may be, so one command cannot become unmanageable.
	 *
	 * <p>Both batching consoles picked 100 independently; it is the same limit for the same
	 * reason, so it lives here.</p>
	 */
	protected static final int MAX_EXAMINE_BLOCK_LEN = 100;

	/**
	 * One cell, its physical address, and whether the console has answered about it yet.
	 *
	 * <p>Where the Pascal stores the physical address in the cell's {@code addr.tmpval} scratch
	 * field and its answered-yet flag in {@code addr.tag} ({@code :984}), this keeps both beside
	 * the cell for the duration of the call. A shared mutable scratch field on a model object is
	 * exactly the kind of thing that stops being safe the moment two things run at once.</p>
	 */
	protected static final class ExamineItem {
		private final MemoryCell m_cell;

		private final Address m_physical;

		private boolean m_done;

		public ExamineItem(MemoryCell cell, Address physical) {
			m_cell = cell;
			m_physical = physical;
		}

		public MemoryCell getCell() {
			return m_cell;
		}

		public Address getPhysical() {
			return m_physical;
		}

		public boolean isDone() {
			return m_done;
		}
	}

	/**
	 * Where one block ends, and the command that reads it.
	 *
	 * @param endExclusive one past the last item this command covers
	 * @param command      what to send, without its line terminator
	 */
	protected record ExamineBlock(int endExclusive, String command) {
	}

	/**
	 * The console-specific half of a bulk examine: how a block is phrased, and how it is sent.
	 *
	 * <p>Everything else - the list passes, the block collection, the "no progress this pass"
	 * termination rule, attributing an answer to a cell - is identical between the batching
	 * consoles and lives in {@link AbstractConsole}. It was written twice, and the hardening
	 * that makes the outer loop terminate had to be applied twice.</p>
	 */
	protected interface BulkExamineProtocol {
		/**
		 * Extend a block starting at {@code blockstart} over as many consecutive not-yet-answered
		 * items as this dialect can ask about in one command.
		 *
		 * @param addrInc 2 between memory words, 1 between the byte-spaced registers
		 */
		ExamineBlock nextBlock(List<ExamineItem> list, int blockstart, int addrInc);

		/**
		 * Send one block command.
		 *
		 * @return the answer index to start scanning replies from - which is not 0 for a console
		 *         that echoes, because its own echo can precede answers to the previous command
		 */
		int sendBlockCommand(String command) throws ConsoleException;
	}

	/**
	 * Read two already-classified lists of cells, then propagate once.
	 *
	 * <p>Memory and registers are separate lists because they step differently, 2 against 1, and
	 * because the registers are usually named rather than addressed. Which cell is which is the
	 * one part of this each dialect decides for itself.</p>
	 */
	protected final void bulkExamine(MemoryCellGroup g, List<ExamineItem> memory,
		List<ExamineItem> registers, ProgressMonitor pm, BulkExamineProtocol proto) throws ConsoleException {
		memory.sort(Comparator.comparingLong(a -> a.m_physical.val()));
		registers.sort(Comparator.comparingLong(a -> a.m_physical.val()));

		pm.begin("Examining ...", memory.size() + registers.size());
		try {
			runExamineList(memory, 2, pm, proto);
			runExamineList(registers, 1, pm, proto);
		} finally {
			pm.done();
		}
		//-- One propagation pass at the end rather than one per word; see MemoryCell.setPdpValue.
		MemoryCellGroups owner = g.getOwner();
		if(owner != null) {
			for(MemoryCell mc : List.copyOf(g.getCells())) {
				owner.syncMemoryCells(mc);
			}
		}
	}

	/**
	 * Keep passing over the list until nothing is left - or until a pass answers nothing new.
	 *
	 * <p>The Pascal's {@code while not examineAddrList(...) do ;} relies on a stated invariant:
	 * every call marks at least one more cell answered. That holds as long as a failure can be
	 * attributed to a cell, and {@link #collectBlock} works hard to keep it true - but "the loop
	 * terminates because a comment says it must" is not a property, it is a hope, and this one
	 * spins forever against a console that answers about an address nobody asked for. Counting
	 * what is left makes termination something the code enforces.</p>
	 */
	private void runExamineList(List<ExamineItem> list, int addrInc, ProgressMonitor pm,
		BulkExamineProtocol proto) throws ConsoleException {
		int outstanding = list.size();
		while(!examineAddrList(list, addrInc, pm, proto)) {
			int now = 0;
			for(ExamineItem it : list) {
				if(!it.m_done)
					now++;
			}
			if(now >= outstanding) {
				m_logger.log(LogChannel.OTHER,
					"EXAMINE list: giving up with " + now + " cell(s) unanswered - no progress this pass");
				return;
			}
			outstanding = now;
		}
	}

	/**
	 * One pass over the not-yet-answered cells.
	 *
	 * @return true when there is nothing left to do - every cell answered, cancelled, or given
	 *         up on. False means "call me again".
	 */
	private boolean examineAddrList(List<ExamineItem> list, int addrInc, ProgressMonitor pm,
		BulkExamineProtocol proto) throws ConsoleException {
		int blockstart = -1;
		for(int i = 0; i < list.size(); i++) {
			if(!list.get(i).m_done) {
				blockstart = i;
				break;
			}
		}
		if(blockstart < 0)
			return true;                                    // all answered, or the list is empty

		boolean blockFailure = false;
		while(!pm.isCancelled() && !blockFailure && blockstart < list.size()) {
			ExamineBlock block = proto.nextBlock(list, blockstart, addrInc);
			if(!collectBlock(list, blockstart, block.endExclusive(), addrInc, block.command(), pm, proto))
				return true;                                // timed out; do not retry, ever
			blockFailure = anyUnanswered(list, blockstart, block.endExclusive());
			blockstart = block.endExclusive();
		}
		return pm.isCancelled() || !blockFailure;
	}

	/**
	 * Send one command and take in its replies.
	 *
	 * <p>Both dialects answer strictly in ascending address order, one line per address, and
	 * simply stop when they hit an address that does not exist - so a UNIBUS timeout ends the
	 * block early and the address it happened at has to be inferred from the last good
	 * answer.</p>
	 *
	 * @return false if the console stopped answering altogether, which is not worth retrying
	 */
	private boolean collectBlock(List<ExamineItem> list, int blockstart, int blockend, int addrInc,
		String cmd, ProgressMonitor pm, BulkExamineProtocol proto) throws ConsoleException {
		int scanFrom = proto.sendBlockCommand(cmd);
		long nextExpected = list.get(blockstart).m_physical.val();

		while(!pm.isCancelled()) {
			if(!anyUnanswered(list, blockstart, blockend))
				return true;
			int at = m_answers.waitForIndex(p -> p instanceof AnswerPhrase.ExamineResult,
				scanFrom, m_commandTimeoutMillis);
			if(at < 0) {
				m_logger.log(LogChannel.OTHER, "EXAMINE list failure: timeout waiting for "
					+ Octal.format(nextExpected, 8));
				return false;
			}
			AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) m_answers.get(at);
			scanFrom = at + 1;

			//-- A timeout answer names no address, so it belongs to whatever was next in line.
			long answerAddr = r.value().isKnown() && r.examineAddr() != null
				? r.examineAddr().val()
				: nextExpected;
			boolean found = false;
			for(int j = blockstart; j < blockend; j++) {
				ExamineItem it = list.get(j);
				if(it.m_physical.val() == answerAddr) {
					nextExpected = answerAddr + addrInc;
					if(!it.m_done)
						pm.step(1);
					it.m_done = true;
					it.m_cell.setPdpValue(r.value());
					found = true;
				}
			}
			if(!found) {
				m_logger.log(LogChannel.OTHER,
					"No memory cell matches the answer \"" + r.rawText() + "\" from " + name());
				if(!r.value().isKnown()) {
					//-- A failure that cannot be attributed would otherwise leave the block
					//-- unanswered forever, and the caller retries until something changes.
					//-- Give up on the first outstanding cell so the retry can make progress.
					for(int j = blockstart; j < blockend; j++) {
						ExamineItem it = list.get(j);
						if(!it.m_done) {
							it.m_done = true;
							it.m_cell.setPdpValue(CellValue.UNKNOWN);
							pm.step(1);
							break;
						}
					}
				}
			}
			if(!r.value().isKnown())
				return true;                                // the block ended here
		}
		return true;
	}

	private static boolean anyUnanswered(List<ExamineItem> list, int from, int to) {
		for(int i = from; i < to; i++) {
			if(!list.get(i).m_done)
				return true;
		}
		return false;
	}

}
