package to.etc.pdp11.core.fake;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Scheduler;

import java.util.Random;

/**
 * A simulated PDP-11 console, good enough to exercise the real protocol code.
 *
 * <p>Ported from {@code TFakePDP11Generic} ({@code FakePDP11GenericU.pas}). These are the
 * primary safety net of PLAN.md §6: they drive the console implementations end to end,
 * headlessly, with no PDP-11 and no SimH. A subclass implements one console's dialect by
 * filling in {@link #serialWriteByte} - everything else, memory and the pretend RUN/HALT
 * switch, lives here.</p>
 *
 * <p>This does <b>not</b> execute PDP-11 instructions. A "run" is a timer that fires after a
 * while and reports a HALT at a plausible address ({@code :191-200}); that is enough to
 * exercise the run/halt/single-step paths of the console protocol, which is all it is for.</p>
 */
public abstract class FakePdp11 {
	/** The I/O page is 8 KB on every PDP-11. */
	public static final int IOPAGE_SIZE = 8192;

	/** Offsets within the I/O page of R0..R7 and the PSW, as 16-bit addresses would give them. */
	private static final int REG_R0 = 017700;

	private static final int REG_R7 = 017707;

	private static final int REG_PSW = 017776;

	private final MemoryAddressType m_type;

	private final String m_name;

	/**
	 * Physical memory, one entry per <b>word</b>.
	 *
	 * <p>The Pascal declares {@code Mem: array[0..FakePDP11_max_addr-1] of word}
	 * ({@code :71}) and indexes it by <i>byte</i> address, so half of a 4 M entry array is
	 * never touched and byte address 1000 and 1001 are different words. Indexing by
	 * {@code addr >> 1} is both smaller and closer to the hardware, where a word is a word
	 * whichever of its two byte addresses you name. Odd addresses are rejected above this
	 * level, by the console, exactly as a real one does.</p>
	 */
	private final short[] m_memory;

	/**
	 * The I/O page, indexed by <b>byte</b> offset - unlike {@link #m_memory} above, and not by
	 * oversight.
	 *
	 * <p>PDP11GUI addresses the console's pseudo-registers R0..R7 at eight <i>consecutive</i>
	 * byte addresses {@code 017700..017707}, not word-spaced. That is its own convention, not
	 * the hardware's, and it runs right through the application: the shipped machine
	 * description writes {@code R0=177700}, {@code R1=177701} and so on, and the ODT fake
	 * computes a register's address as {@code base + 017700 + n}
	 * ({@code FakePDP11ODTU.pas:377}). Halving the index here would put R0 and R1 in the same
	 * slot and quietly alias every register pair.</p>
	 *
	 * <p>Real device registers in the I/O page are word-spaced and simply leave the odd slots
	 * unused, so byte indexing costs 8 KB and breaks nothing.</p>
	 */
	private final short[] m_ioPage = new short[IOPAGE_SIZE];

	/**
	 * Which I/O page addresses exist. Everything else reads back as a nonexistent-memory
	 * error, which is what makes the I/O page scanner meaningful against a fake.
	 */
	private final boolean[] m_ioPageValid = new boolean[IOPAGE_SIZE];

	private final long m_physicalMemorySize;

	private final long m_iopageBase;

	/** The pretend front-panel RUN/HALT switch. Not used by the 11/44 or SimH consoles. */
	private boolean m_runMode;

	private final Scheduler m_scheduler;

	private final Random m_random;

	private Scheduler.Handle m_runHandle;

	/**
	 * Told whenever the console prints something.
	 *
	 * <p>Needed because output is not always a reply: the simulated run-to-halt fires on the
	 * scheduler's thread and prints the halt report ({@code FakePDP11GenericU.pas:212-227}).
	 * Without a way to announce that, a reader blocked waiting for bytes would sit there until
	 * the next keystroke - which for a program that halts on its own is forever.</p>
	 */
	private Runnable m_outputListener;

	/** Characters the user has typed that this console has not consumed. */
	private final StringBuilder m_serialIn = new StringBuilder();

	/** Characters this console has printed that have not been read yet. */
	private final StringBuilder m_serialOut = new StringBuilder();

	/**
	 * @param type       16, 18 or 22 bit; decides the I/O page base and how much memory is
	 *                   fitted
	 * @param scheduler  drives the simulated run-to-halt; use {@link Scheduler.Manual} in tests
	 * @param random     the run duration and halt address; seed it in tests
	 */
	protected FakePdp11(String name, MemoryAddressType type, Scheduler scheduler, Random random) {
		if(!type.isConcretePhysical())
			throw new IllegalArgumentException("A fake PDP-11 needs a concrete physical width, not " + type);
		m_name = name;
		m_type = type;
		m_scheduler = scheduler;
		m_random = random;
		m_iopageBase = type.getIopageBase();
		//-- Half-populated, as the Pascal does it (:132-140): 32 KB on a 16-bit machine,
		//-- 128 KB on an 18-bit one, 1 MB on a 22-bit one. Leaving the top half missing is
		//-- deliberate - it gives the tests real nonexistent-memory addresses to aim at.
		m_physicalMemorySize = switch(type) {
			case PHYSICAL16 -> 0x8000L;
			case PHYSICAL18 -> 0x20000L;
			default -> 0x100000L;
		};
		m_memory = new short[(int) (m_physicalMemorySize / 2)];
		resetIoPageValidMap(null, null);
	}

	public String getName() {
		return m_name;
	}

	public MemoryAddressType getAddressType() {
		return m_type;
	}

	public long getPhysicalMemorySize() {
		return m_physicalMemorySize;
	}

	public long getIopageBase() {
		return m_iopageBase;
	}

	/** R7's address in the I/O page, which this needs constantly. */
	public Address getProgramCounterAddr() {
		return Address.of(m_type, m_iopageBase + REG_R7);
	}

	public boolean isRunMode() {
		return m_runMode;
	}

	/** Flip the pretend front-panel switch. Decides whether {@code G} and {@code P} run or step. */
	public void setRunMode(boolean runMode) {
		m_runMode = runMode;
	}

	// -------------------------------------------------------------------------------------
	// Memory
	// -------------------------------------------------------------------------------------

	/**
	 * Read one word.
	 *
	 * @throws FakePdp11Exception for an address that is neither fitted memory nor an
	 *                            implemented I/O page register
	 */
	public int getMem(Address addr) {
		long a = addr.val();
		if(a <= m_physicalMemorySize - 2)
			return m_memory[(int) (a >> 1)] & 0xFFFF;
		int io = ioPageIndex(a);
		if(io >= 0 && m_ioPageValid[io])
			return m_ioPage[io] & 0xFFFF;
		throw new FakePdp11Exception("Nonexistent memory at " + addr.toOctal());
	}

	/** Write one word, with the same rules as {@link #getMem}. */
	public void setMem(Address addr, int value) {
		long a = addr.val();
		if(a <= m_physicalMemorySize - 2) {
			m_memory[(int) (a >> 1)] = (short) value;
			return;
		}
		int io = ioPageIndex(a);
		if(io >= 0 && m_ioPageValid[io]) {
			m_ioPage[io] = (short) value;
			return;
		}
		throw new FakePdp11Exception("Nonexistent memory at " + addr.toOctal());
	}

	/** Whether an address exists on this machine at all. */
	public boolean isImplemented(Address addr) {
		long a = addr.val();
		if(a <= m_physicalMemorySize - 2)
			return true;
		int io = ioPageIndex(a);
		return io >= 0 && m_ioPageValid[io];
	}

	private int ioPageIndex(long addr) {
		long off = addr - m_iopageBase;
		if(off < 0 || off >= IOPAGE_SIZE)
			return -1;
		return (int) off;
	}

	/** Forget all of memory. Ported from the {@code PowerOn} implementations. */
	protected void clearMemory() {
		java.util.Arrays.fill(m_memory, (short) 0);
		java.util.Arrays.fill(m_ioPage, (short) 0);
	}

	/**
	 * Decide which I/O page addresses this machine answers to: R0..R7 and the PSW always, plus
	 * every register a machine description declares under {@code usageTag}.
	 *
	 * <p>Ported from {@code CalcIoPageValidMap} ({@code :232-266}). The unconditional CPU
	 * registers are why the fakes were usable before the machine descriptions were recovered
	 * (PLAN.md §7); now that they exist, passing them in gives a fake a realistic I/O page and
	 * makes the I/O page scanner worth testing.</p>
	 *
	 * @param groups   may be {@code null} for CPU registers only
	 * @param usageTag which groups to take, or {@code null} for all of them
	 */
	public final void resetIoPageValidMap(MemoryCellGroups groups, String usageTag) {
		java.util.Arrays.fill(m_ioPageValid, false);
		//-- R0..R7 sit at consecutive byte offsets 017700..017707; see m_ioPage.
		for(int off = REG_R0; off <= REG_R7; off++) {
			m_ioPageValid[off] = true;
		}
		m_ioPageValid[REG_PSW] = true;
		if(groups == null)
			return;

		for(MemoryCellGroup g : groups.getGroups()) {
			if(usageTag != null && !usageTag.equals(g.getUsageTag()))
				continue;
			for(MemoryCell cell : g.getCells()) {
				Address a = cell.getAddr();
				if(!a.type().isConcretePhysical() || !a.isInIopage())
					continue;
				int io = ioPageIndex(a.withWidth(m_type).val());
				if(io >= 0)
					m_ioPageValid[io] = true;
			}
		}
	}

	// -------------------------------------------------------------------------------------
	// The pretend RUN/HALT switch
	// -------------------------------------------------------------------------------------

	/**
	 * Pretend to start executing at {@code startPc}, and to hit a HALT somewhere in the next
	 * one to five seconds. Ported from {@code RunToHalt} ({@code :191-200}).
	 */
	public void runToHalt(long startPc) {
		setMem(getProgramCounterAddr(), (int) startPc);
		cancelRun();
		m_runHandle = m_scheduler.schedule(this::onRunToHaltTimer, 1000 + m_random.nextInt(4000));
	}

	public boolean isRunning() {
		return m_runHandle != null && m_runHandle.isPending();
	}

	/** Stop pretending to run, without printing anything. The front panel HALT switch. */
	public void haltSwitch() {
		cancelRun();
	}

	private void cancelRun() {
		if(m_runHandle != null) {
			m_runHandle.cancel();
			m_runHandle = null;
		}
	}

	/**
	 * The simulated program ran into a HALT. Ported from {@code OnRunToHaltTimer}
	 * ({@code :212-227}): the PC lands somewhere in the next 64 bytes, always even.
	 */
	private synchronized void onRunToHaltTimer() {
		cancelRun();
		long pc = getMem(getProgramCounterAddr());
		pc += m_random.nextInt(0077) + 2;
		if((pc & 1) != 0)
			pc++;
		setMem(getProgramCounterAddr(), (int) pc);
		doHalt();
	}

	// -------------------------------------------------------------------------------------
	// The serial line
	// -------------------------------------------------------------------------------------

	/**
	 * Take one byte from the console's output, if it has any.
	 *
	 * @return -1 when there is nothing to read
	 */
	public int serialReadByte() {
		if(m_serialOut.isEmpty())
			return -1;
		char c = m_serialOut.charAt(0);
		m_serialOut.deleteCharAt(0);
		return c & 0xFF;
	}

	/** How many bytes are waiting to be read. */
	public int available() {
		return m_serialOut.length();
	}

	/** Feed one typed character into the console's state machine. */
	public abstract void serialWriteByte(int b);

	/** Everything the console has printed and not yet had read, for tests. */
	public String takeOutput() {
		String s = m_serialOut.toString();
		m_serialOut.setLength(0);
		return s;
	}

	/**
	 * Called when output appears, including output produced by the run-to-halt timer rather
	 * than by a keystroke. May be called from the scheduler's thread.
	 */
	public synchronized void setOutputListener(Runnable outputListener) {
		m_outputListener = outputListener;
	}

	/** The console prints. */
	protected void print(String s) {
		m_serialOut.append(s);
		fireOutput();
	}

	protected void print(char c) {
		m_serialOut.append(c);
		fireOutput();
	}

	private void fireOutput() {
		Runnable l = m_outputListener;
		if(l != null)
			l.run();
	}

	/** The partially typed line. */
	protected String getInputBuffer() {
		return m_serialIn.toString();
	}

	protected void appendInput(char c) {
		m_serialIn.append(c);
	}

	protected void clearInput() {
		m_serialIn.setLength(0);
	}

	protected Random getRandom() {
		return m_random;
	}

	/** Wipe memory and start from the power-on state. */
	public abstract void powerOn();

	/** The console's reset, as the user would cause it. */
	public abstract void reset();

	/** Print whatever this console prints when the machine stops. */
	protected abstract void doHalt();
}
