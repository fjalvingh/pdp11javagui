package to.etc.pdp11.core.machine;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.ConsoleFeature;
import to.etc.pdp11.core.mem.CellValue;
import to.etc.pdp11.core.mem.MemoryCell;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Octal;
import to.etc.pdp11.core.util.ProgressMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads every address in the I/O page to find out which ones exist, names what it finds, and
 * writes the machine-description text for the rest.
 *
 * <p>Ported from {@code TFormIopageScanner.StartScanButtonClick}
 * ({@code FormIoPageScannerU.pas:130-300}). It lives in the core rather than in the window
 * because all of it is arithmetic over what the machine answered - which addresses replied,
 * which of them the description already knows, how the unknown ones group into devices - and
 * because that makes it testable against the simulated machines, which have a real I/O page
 * map for exactly this purpose.</p>
 *
 * <h2>What it is for</h2>
 *
 * <p>You point it at a machine nobody has a description of and it tells you what is plugged
 * into it: 4096 addresses, one examine each, and the ones that answer are devices. Addresses
 * the loaded description already names are labelled from it; consecutive runs of unnamed ones
 * are almost certainly one device apiece, so it invents {@code device_177560.reg_0} names for
 * them and emits an {@code .ini} section the user can paste into their own description.</p>
 *
 * <h2>Why it examines one address at a time</h2>
 *
 * <p>The consoles can read a range in one round trip, and this deliberately does not use that:
 * "sehr viele Adressen, sehr viele ungültige Adressen" ({@code :189-190}). Most of the I/O page
 * is empty, a bulk read of mostly-nonexistent addresses is where the dialects behave least
 * predictably, and a scan that has to be restartable and cancellable wants one answer per
 * question.</p>
 */
public final class IoPageScanner {
	/** The I/O page is the top 8 KB of physical memory on every PDP-11. */
	public static final int IOPAGE_SIZE_BYTES = 8192;

	public static final int IOPAGE_WORDS = IOPAGE_SIZE_BYTES / 2;

	/** Where the description writes an I/O page address: always 16-bit, {@code 0160000} up. */
	private static final MemoryAddressType DESCRIPTION_WIDTH = MemoryAddressType.PHYSICAL16;

	/**
	 * A run of consecutive addresses that answered and that the description does not name.
	 *
	 * @param start the first address, as the 16-bit form a description is written in
	 * @param words how many consecutive words
	 */
	public record Block(Address start, int words) {
		public Address end() {
			return start.plus(2L * (words - 1));
		}
	}

	/**
	 * @param examined  how many addresses were tried
	 * @param found     how many answered
	 * @param named     how many of those the loaded description already knows
	 * @param blocks    the runs of unknown registers, in address order
	 * @param description the {@code .ini} text for {@code blocks}, ready to paste
	 * @param cancelled whether the user stopped it early - the results so far are still good
	 */
	public record Result(int examined, int found, int named, List<Block> blocks, String description,
		boolean cancelled) {
	}

	/**
	 * Told what the scan is finding, while it is finding it.
	 *
	 * <p>A scan is four thousand examines, and over a serial line that is minutes. The Pascal
	 * shows nothing for all of it and then everything at once; a window that fills in as the
	 * addresses answer is telling the user the same thing at the time it is worth knowing - and
	 * a scan they can watch is one they can decide to stop.</p>
	 *
	 * <p>Both methods are called <b>on the command thread</b>, so an implementation that touches
	 * a window marshals for itself.</p>
	 */
	public interface Listener {
		/**
		 * The target group has been emptied and re-expressed at this machine's address width,
		 * and nothing is in it yet.
		 */
		void scanStarted();

		/** One more address answered, and its cell is in the target group now. */
		void addressFound(MemoryCell cell);

		/** Accepts everything and does nothing. For a caller with no window to fill in. */
		Listener NULL = new Listener() {
			@Override
			public void scanStarted() {
			}

			@Override
			public void addressFound(MemoryCell cell) {
			}

			@Override
			public String toString() {
				return "IoPageScanner.Listener.NULL";
			}
		};
	}

	private IoPageScanner() {
	}

	/**
	 * Scan, filling {@code target} with what answered.
	 *
	 * <p>On the command thread. {@code target} is cleared and rebuilt: it holds only the
	 * addresses that exist when this returns.</p>
	 *
	 * @throws ConsoleException if this machine stops dead on a bus timeout, in which case
	 *                          scanning it would halt it 4000 times
	 */
	public static Result scan(Console console, MemoryCellGroups groups, MemoryCellGroup target,
		ProgressMonitor pm) throws ConsoleException {
		return scan(console, groups, target, pm, Listener.NULL);
	}

	/**
	 * The same, telling {@code listener} about each address as it answers.
	 *
	 * <p>The addresses go into {@code target} as they are found rather than in one go at the end,
	 * so a window watching the group can show a scan filling in. What that costs is that a scan
	 * which is cancelled or which fails leaves the group holding what it got to - which is the
	 * point: those addresses did answer, and throwing them away because the scan did not finish
	 * would be throwing away the only reason to stop it early.</p>
	 */
	public static Result scan(Console console, MemoryCellGroups groups, MemoryCellGroup target,
		ProgressMonitor pm, Listener listener) throws ConsoleException {
		if(!console.features().contains(ConsoleFeature.NON_FATAL_UNIBUS_TIMEOUT)) {
			//-- The Pascal puts this in a message box from inside the scan ({@code :152-159}).
			//-- Same words, thrown instead, because the core has no dialogs.
			throw new ConsoleException("To scan the I/O page the machine must carry on after an"
				+ " invalid bus address is accessed (UNIBUS timeout). This machine stops on a UNIBUS"
				+ " timeout, so the I/O page scanner cannot run.");
		}
		MemoryAddressType type = console.physicalAddressType();
		long base = type.getIopageBase();

		//-- Retype the target to the machine's width before storing anything in it, and before
		//-- the scan rather than after it. A group refuses a cell whose address is not its own
		//-- width, and the window creates this one at 22 bits before it knows what it is connected
		//-- to - so on a 16- or 18-bit machine the scan used to run to completion, minutes of it
		//-- over a serial line, and then throw on the first address it stored and lose the lot.
		//-- shiftRange with no words is the way to empty a group and re-express it: clear() keeps
		//-- the type it was created with.
		target.shiftRange(Address.of(type, base), 0, false);
		listener.scanStarted();

		//-- Straight into the group, one address at a time. The Pascal adds all 4096 cells,
		//-- examines them and then deletes the ones that did not answer, which is a rebuild of the
		//-- group's index per deletion; adding only what survives is the same result, and it is
		//-- also what lets a window show the scan filling in rather than nothing for minutes.
		boolean cancelled = false;
		int examined = 0;
		int named = 0;
		pm.begin("Scanning the I/O page ...", IOPAGE_WORDS);
		try {
			for(int i = 0; i < IOPAGE_WORDS; i++) {
				if(pm.isCancelled()) {
					cancelled = true;
					break;
				}
				Address a = Address.of(type, base + 2L * i);
				CellValue v = console.examine(a);
				examined++;
				pm.step(1);
				//-- A bus timeout is an answer: it means nothing is there.
				if(!v.isKnown())
					continue;
				MemoryCell mc = target.add(a);
				mc.setPdpValue(v);
				mc.setEditValue(v);
				//-- Named here rather than in a pass afterwards, so the row the window has just
				//-- shown says "DL11.RCSR" instead of appearing blank and being relabelled later.
				if(nameFromDescription(groups, mc))
					named++;
				listener.addressFound(mc);
			}
		} finally {
			pm.done();
		}

		List<Block> blocks = new ArrayList<>();
		String description = describeUnknownBlocks(target, blocks);
		return new Result(examined, target.size(), named, List.copyOf(blocks), description, cancelled);
	}

	/**
	 * Label the addresses the loaded description already knows.
	 *
	 * <p>{@code getSymbolInfoCell} finds another cell at the same address that carries a name;
	 * the name is prefixed with its group, so a scan result reads {@code DL11.RCSR} rather than
	 * {@code RCSR} and says which device it belongs to ({@code :211-222}).</p>
	 */
	private static boolean nameFromDescription(MemoryCellGroups groups, MemoryCell mc) {
		MemoryCell symbol = groups.findNamedCellAt(mc);
		if(symbol == null)
			return false;
		mc.setName(symbol.getGroup().getGroupName() + "." + symbol.getName());
		mc.setInfo(symbol.getInfo());
		return true;
	}

	/**
	 * Treat each run of consecutive unnamed addresses as one device, and write it up.
	 *
	 * <p>A block ends at a gap in the addresses or at an address the description already names
	 * ({@code :227-243}) - so a device the description knows about splits the unknown ones
	 * either side of it, which is what you want: they are different devices.</p>
	 */
	private static String describeUnknownBlocks(MemoryCellGroup target, List<Block> blocks) {
		StringBuilder sb = new StringBuilder();
		List<MemoryCell> cells = target.getCells();
		int i = 0;
		while(i < cells.size()) {
			MemoryCell start = cells.get(i);
			if(!start.getName().isEmpty()) {
				i++;                                        // already known; skip it
				continue;
			}
			int blockStart = i;
			while(i < cells.size()
				&& cells.get(i).getName().isEmpty()
				&& cells.get(i).getAddr().val() == start.getAddr().val() + 2L * (i - blockStart)) {
				i++;
			}
			int words = i - blockStart;
			//-- Descriptions are written in 16-bit I/O page addresses whatever the machine is;
			//-- the Pascal does that subtraction by hand as "addr - iopagebase + 0160000".
			Address start16 = start.getAddr().withWidth(DESCRIPTION_WIDTH);
			blocks.add(new Block(start16, words));

			for(int j = 0; j < words; j++) {
				MemoryCell mc = cells.get(blockStart + j);
				mc.setName("device_" + Octal.format(start16.val(), 6) + ".reg_" + Octal.format(2L * j, 1));
				mc.setInfo("device base at " + Octal.format(start16.val(), 6) + ", word #" + j
					+ ", octal offset +" + Octal.format(2L * j, 1));
			}
			appendSection(sb, start16, cells, blockStart, words);
		}
		return sb.toString();
	}

	/** One {@code [Device_nnnnnn]} section, in the form a description file takes. */
	private static void appendSection(StringBuilder sb, Address start16, List<MemoryCell> cells,
		int blockStart, int words) {
		String startText = Octal.format(start16.val(), 6);
		sb.append('\n');
		sb.append("[Device_").append(startText).append("]\n");
		sb.append("Info=register block at ").append(startText).append(" with ").append(words)
			.append(" words len.\n");
		sb.append("Enabled=true\n");
		if(words <= 16) {
			//-- Few enough to be worth listing one per line, which is what a device section
			//-- normally looks like and what the user will want to edit.
			for(int j = 0; j < words; j++) {
				MemoryCell mc = cells.get(blockStart + j);
				sb.append("Register_").append(Octal.format(2L * j, 1)).append('=')
					.append(Octal.format(mc.getAddr().withWidth(DESCRIPTION_WIDTH).val(), 1))
					.append(";\"").append(mc.getInfo()).append("\"\n");
			}
		} else {
			//-- A long consecutive run is not a register set. Say so rather than emitting
			//-- hundreds of lines.
			Address end16 = start16.plus(2L * (words - 1));
			sb.append("Registers=").append(startText).append(':').append(Octal.format(end16.val(), 6))
				.append(";\"").append(words).append(" consecutive words ... ROM or RAM?\"\n");
		}
	}

	/** The header the result pane starts with, before anything has been scanned. */
	public static String emptyDescriptionHint() {
		return "; After a scan this shows the I/O page addresses that answered.\n"
			+ "; Mark the part you want and paste it into your machine description file.\n";
	}
}
