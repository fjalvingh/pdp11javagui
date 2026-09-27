package to.etc.pdp11.core.mem;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one {@link MemoryCellGroups} is reached from three kinds of thread at once, and must
 * survive it.
 *
 * <p>FABLE-ISSUES #64: a connect attempt builds an MMU, which adds a group and fills it, while
 * an attempt that lost the race removes its own group again - and meanwhile something walks the
 * groups. That walk is what threw, intermittently, out of {@code ConnectionManager.connect}:</p>
 *
 * <pre>
 * java.util.ConcurrentModificationException
 *     at to.etc.pdp11.core.fake.FakePdp11.resetIoPageValidMap(FakePdp11.java:299)
 *     at to.etc.pdp11.core.conn.ConnectionManager.connect(ConnectionManager.java:288)
 * </pre>
 *
 * <p>This reproduces that shape without a connection in it: one thread doing what building and
 * abandoning a console does to the groups, one thread doing what the walk does. It fails within
 * a few dozen iterations when {@code getGroups()} and {@code getCells()} hand out views of the
 * live lists, which is what they used to do.</p>
 */
class MemoryCellGroupsThreadingTest {
	/** Long enough to lose the race on any machine, short enough not to be noticed. */
	private static final int ROUNDS = 400;

	@Test
	void aWalkSurvivesGroupsBeingAddedAndRemovedUnderIt() throws Exception {
		MemoryCellGroups groups = new MemoryCellGroups();
		//-- A group nobody touches, so the walker always has something to walk into.
		MemoryCellGroup fixed = groups.addGroup(MemoryAddressType.PHYSICAL22, "machine");
		fixed.add(0760000L, 64);

		AtomicReference<Throwable> died = new AtomicReference<>();
		CountDownLatch go = new CountDownLatch(1);

		Thread connecting = new Thread(() -> {
			try {
				go.await();
				for(int i = 0; i < ROUNDS; i++) {
					//-- What Pdp11Mmu's constructor does, and what abandoning an attempt undoes.
					MemoryCellGroup mmu = groups.addGroup(MemoryAddressType.PHYSICAL22, "MMU");
					mmu.add(0772300L, 32);
					groups.removeGroup(mmu);
				}
			} catch(Throwable x) {
				died.compareAndSet(null, x);
			}
		}, "connecting");

		Thread walking = new Thread(() -> {
			try {
				go.await();
				for(int i = 0; i < ROUNDS; i++) {
					//-- resetIoPageValidMap's walk, to the letter: every group, every cell.
					for(MemoryCellGroup g : groups.getGroups()) {
						for(MemoryCell cell : g.getCells()) {
							Address a = cell.getAddr();
							assertTrue(a.type().isConcretePhysical());
						}
					}
				}
			} catch(Throwable x) {
				died.compareAndSet(null, x);
			}
		}, "walking");

		connecting.start();
		walking.start();
		go.countDown();
		connecting.join(30_000);
		walking.join(30_000);
		assertNull(died.get(), () -> "walking the groups threw: " + died.get());
	}
}
