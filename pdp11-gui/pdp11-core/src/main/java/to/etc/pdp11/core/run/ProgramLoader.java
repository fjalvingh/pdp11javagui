package to.etc.pdp11.core.run;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.memfile.AbsoluteLoaderTape;
import to.etc.pdp11.common.util.OperationCancelledException;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;

import java.util.List;

/**
 * Puts a standalone program into a machine with nothing but deposits, and starts it.
 *
 * <p>This is what the absolute loader does, done from outside: every word the image loads is
 * deposited through the console, the switch register is set if asked, and the machine is reset and
 * started at the program's start address. It needs no loader in the machine, no boot device and no
 * monitor - only a console that can deposit and start, which is every console this application
 * talks to. It is slow over a serial line (a 12K-word diagnostic is some 12,000 deposits) and
 * instant over SimH.</p>
 *
 * <p>Runs on the command thread, like every console call: see PLAN.md §1. The words go through a
 * temporary {@link MemoryCellGroup}, which is what makes every open memory window show what was
 * written, and is removed again afterwards.</p>
 */
public final class ProgramLoader {
	/** The software switch register of standalone diagnostics, used when there is no hardware one. */
	public static final int SOFTWARE_SWITCH_REGISTER = 0176;

	private ProgramLoader() {
	}

	/**
	 * What was done.
	 *
	 * @param words    how many words were deposited
	 * @param examined how many of them were half-words, examined first so the other byte survived
	 */
	public record Loaded(int words, int examined) {
	}

	/**
	 * Deposit the image.
	 *
	 * <p>A word the image loads only one byte of is examined first and the other byte kept, as
	 * the absolute loader would leave it; they are rare - a block that ends on an odd address.</p>
	 *
	 * @param switchRegister the value for location 176, or null to leave what the image put there
	 * @throws OperationCancelledException when the monitor is cancelled; the machine then holds
	 *                                     part of the program and must not be started
	 */
	public static Loaded load(Console console, MemoryCellGroups groups, AbsoluteLoaderTape image, Integer switchRegister,
		ProgressMonitor monitor) throws ConsoleException {
		MemoryAddressType type = console.physicalAddressType();
		List<AbsoluteLoaderTape.Word> words = image.words();
		if(words.isEmpty())
			throw new IllegalArgumentException("The image loads nothing");
		MemoryCellGroup group = groups.addGroup(type, "Program load");
		group.setUsageTag("programload");
		//-- What is in here is about to be written, and nothing must replace it with what the
		//-- machine holds before it is.
		group.setPdpOverwritesEdit(false);
		int examined = 0;
		try {
			for(AbsoluteLoaderTape.Word w : words) {
				int value = w.value();
				if(!w.whole()) {
					monitor.checkCancelled();
					int now = console.examine(Address.of(type, w.address())).wordOr(0);
					boolean lowLoaded = image.isLoaded(w.address());
					value = lowLoaded ? (now & 0xFF00) | (value & 0x00FF) : (now & 0x00FF) | (value & 0xFF00);
					examined++;
				}
				group.add(Address.of(type, w.address())).setEditValue(CellValue.of(value));
			}
			if(switchRegister != null) {
				MemoryCell swr = group.findByAddress(Address.of(type, SOFTWARE_SWITCH_REGISTER));
				if(swr == null)
					swr = group.add(Address.of(type, SOFTWARE_SWITCH_REGISTER));
				swr.setEditValue(CellValue.of(switchRegister & 0xFFFF));
			}
			group.sort();
			console.deposit(group, false, monitor);
			//-- A cancelled deposit stops quietly between two words. Say so, loudly: half a program
			//-- must not be mistaken for a loaded one and started.
			monitor.checkCancelled();
			return new Loaded(group.getCells().size(), examined);
		} finally {
			groups.removeGroup(group);
		}
	}

	/** Reset the machine and start it at {@code address}. */
	public static void start(Console console, int address) throws ConsoleException {
		console.resetAndStart(Address.of(MemoryAddressType.VIRTUAL, address));
	}
}
