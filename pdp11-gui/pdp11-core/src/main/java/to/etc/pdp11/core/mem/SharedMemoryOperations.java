package to.etc.pdp11.core.mem;

import to.etc.pdp11.common.addr.Address;
import to.etc.pdp11.common.addr.MemoryAddressType;
import to.etc.pdp11.common.mem.CellValue;
import to.etc.pdp11.common.mem.MemoryCell;
import to.etc.pdp11.common.mem.MemoryCellGroup;
import to.etc.pdp11.common.mem.MemoryCellGroups;
import to.etc.pdp11.common.mem.SharedMemory;
import to.etc.pdp11.common.util.ProgressMonitor;
import to.etc.pdp11.core.console.Console;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.mmu.Pdp11Mmu;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * What is done to shared memory as a whole rather than to one window's range: deposit everything
 * pending, read again what the windows hold, and follow the MMU when it has moved. PLAN.md §1,
 * "Shared memory".
 *
 * <p>Each runs on the command thread, against the console it is handed. A console works on
 * groups, so each builds a group for the duration over the words concerned - at the console's
 * own physical width, which is what it expects - and gives it back afterwards. The cells of such
 * a group are views on the same shared words every window shows, so depositing or reading them
 * is depositing or reading those.</p>
 */
public final class SharedMemoryOperations {
	private SharedMemoryOperations() {
	}

	/**
	 * @param deposited   words that were pending and are now in the machine
	 * @param unreachable words pending at an address this machine's bus cannot reach - a 22-bit
	 *                    address on a 16-bit machine - which stay pending
	 */
	public record DepositResult(int deposited, int unreachable) {
	}

	/**
	 * @param registersChanged MMU and PSW registers that read differently from last time
	 * @param moved            groups with virtual cells that now name different words
	 */
	public record MmuCheck(int registersChanged, List<MemoryCellGroup> moved) {
	}

	/** Deposit every word in shared memory with an edit the machine is not known to hold. */
	public static DepositResult depositPending(MemoryCellGroups groups, Console console, ProgressMonitor pm)
		throws ConsoleException {
		List<SharedMemory.PendingWord> pending = groups.getSharedMemory().getPending();
		if(pending.isEmpty())
			return new DepositResult(0, 0);
		List<Address> addresses = new ArrayList<>(pending.size());
		for(SharedMemory.PendingWord w : pending) {
			addresses.add(w.address());
		}
		MemoryAddressType type = console.physicalAddressType();
		MemoryCellGroup group = groups.addGroup(type, "Deposit changed");
		try {
			int unreachable = fill(group, addresses, type);
			console.deposit(group, true, pm);
			int deposited = 0;
			for(MemoryCell mc : group.getCells()) {
				if(!mc.isEdited())
					deposited++;
			}
			return new DepositResult(deposited, unreachable);
		} finally {
			groups.removeGroup(group);
		}
	}

	/**
	 * Read again every word of memory some window holds, each once however many windows hold it.
	 *
	 * <p>Not every word shared memory knows: that grows with everything ever looked at, and at
	 * serial speeds rereading it is not something to offer as one button. What the windows hold
	 * is what anybody is looking at.</p>
	 *
	 * @return how many words were read
	 */
	public static int rereadShown(MemoryCellGroups groups, Console console, ProgressMonitor pm) throws ConsoleException {
		TreeSet<Long> words = new TreeSet<>();
		for(MemoryCellGroup g : groups.getGroups()) {
			for(MemoryCell mc : g.getCells()) {
				if(mc.isShared())
					words.add(mc.getPhysical().val());
			}
		}
		if(words.isEmpty())
			return 0;
		List<Address> addresses = new ArrayList<>(words.size());
		for(long v : words) {
			addresses.add(Address.of(MemoryAddressType.PHYSICAL22, v));
		}
		MemoryAddressType type = console.physicalAddressType();
		MemoryCellGroup group = groups.addGroup(type, "Reread shown");
		try {
			fill(group, addresses, type);
			console.examine(group, false, pm);
			return group.size();
		} finally {
			groups.removeGroup(group);
		}
	}

	/**
	 * Read the MMU's registers and the PSW again, and move every virtual view whose addresses now
	 * map to other words.
	 *
	 * <p>The MMU translates from the registers it last saw, and assumes relocation off until it
	 * has seen any - so a program that set the MMU up, or changed it, leaves every virtual window
	 * showing the words the old mapping named. Nothing in shared memory is invalidated: see
	 * {@link MemoryCellGroups#reresolveVirtual}.</p>
	 *
	 * <p>The MMU's own register group has to be re-evaluated after it is examined: propagation
	 * skips the cell it started from, so examining the MMU's group never reaches the MMU's own
	 * listener (PLAN.md, phase 6 part 8).</p>
	 *
	 * @return what changed, or {@code null} for a console with no MMU
	 */
	public static MmuCheck checkMmu(MemoryCellGroups groups, Console console, ProgressMonitor pm) throws ConsoleException {
		Pdp11Mmu mmu = console.getMmu();
		if(mmu == null)
			return null;
		List<MemoryCell> registers = mmu.getRegisterGroup().getCells();
		List<CellValue> before = new ArrayList<>(registers.size());
		for(MemoryCell mc : registers) {
			before.add(mc.getPdpValue());
		}
		console.examine(mmu.getRegisterGroup(), false, pm);
		mmu.evalAll();
		int changed = 0;
		for(int i = 0; i < registers.size(); i++) {
			if(!registers.get(i).getPdpValue().equals(before.get(i)))
				changed++;
		}
		return new MmuCheck(changed, groups.reresolveVirtual());
	}

	/** Add a cell per address at {@code type}, skipping those it cannot express. */
	private static int fill(MemoryCellGroup group, List<Address> addresses, MemoryAddressType type) {
		int unreachable = 0;
		for(Address a : addresses) {
			if(a.fitsWidth(type))
				group.add(a.withWidth(type));
			else
				unreachable++;
		}
		return unreachable;
	}
}
