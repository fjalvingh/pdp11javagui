package to.etc.pdp11.core.memtest;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.console.ConsoleConnection;
import to.etc.pdp11.core.console.ConsoleException;
import to.etc.pdp11.core.console.SimhConsole;
import to.etc.pdp11.core.fake.FakeSimh;
import to.etc.pdp11.core.io.FakeTransport;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.ProgressMonitor;
import to.etc.pdp11.core.util.Scheduler;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memory tests, pointed at memory that is deliberately broken.
 *
 * <p>A diagnostic tool nobody has shown a real fault to is a guess. The original's author knew
 * that - {@code TestSingleBit} carries two commented-out lines, "bit 8 always H" and "bit 15
 * always L", which is him hand-editing the program to check his own test could see a fault
 * ({@code FormMemoryTestU.pas:388-390}). The simulated machines can be broken on purpose now, so
 * the same check runs on every build.</p>
 */
class MemoryTesterTest {
	private static final int WORDS = 64;

	/** A connected SimH console over a fake machine, with a group covering the test range. */
	private static final class Rig implements AutoCloseable {
		final FakeSimh fake = new FakeSimh(new Scheduler.Manual(), new Random(42));

		final MemoryCellGroups groups = new MemoryCellGroups();

		final MemoryCellGroup group;

		final ConsoleConnection connection;

		final SimhConsole console;

		Rig() throws ConsoleException {
			fake.powerOn();
			connection = new ConsoleConnection(new FakeTransport(fake), Logger.NULL);
			console = new SimhConsole(groups, Logger.NULL, new Scheduler.Manual());
			connection.attach(console);
			connection.run(() -> console.init(connection));
			group = groups.addGroup(MemoryAddressType.PHYSICAL22, "test");
			group.add(01000, WORDS);
		}

		MemoryTester tester(ChipSize size) {
			return new MemoryTester(console, group, addr(01000), addr(01000 + 2L * (WORDS - 1)), size, null);
		}

		/** Run on the command thread, which is where every console call belongs. */
		<T> T call(java.util.concurrent.Callable<T> work) throws ConsoleException {
			return connection.call(() -> {
				try {
					return work.call();
				} catch(ConsoleException x) {
					throw x;
				} catch(Exception x) {
					throw new IllegalStateException(x);
				}
			});
		}

		@Override
		public void close() {
			connection.close();
		}
	}

	private static Address addr(long v) {
		return Address.of(MemoryAddressType.PHYSICAL22, v);
	}

	// ---------------------------------------------------------------------------------------
	// Working memory passes
	// ---------------------------------------------------------------------------------------

	@Test
	void goodMemoryPassesEveryTest() throws Exception {
		try(Rig rig = new Rig()) {
			MemoryTester t = rig.tester(ChipSize.WORD);
			assertTrue(rig.call(() -> t.testDataLines(true, ProgressMonitor.NULL)).passed(), "moving one");
			assertTrue(rig.call(() -> t.testDataLines(false, ProgressMonitor.NULL)).passed(), "moving zero");
			assertTrue(rig.call(() -> t.testAddressLines(1, ProgressMonitor.NULL)).passed(), "address lines 1");
			assertTrue(rig.call(() -> t.testAddressLines(2, ProgressMonitor.NULL)).passed(), "address lines 2");
			assertTrue(rig.call(() -> t.testDataBits(1, ProgressMonitor.NULL)).passed(), "data bits 1");
			assertTrue(rig.call(() -> t.testRandom(16, new Random(7), ProgressMonitor.NULL)).passed(), "random");
		}
	}

	// ---------------------------------------------------------------------------------------
	// Broken memory is diagnosed
	// ---------------------------------------------------------------------------------------

	@Test
	void aDataLineTiedHighIsFoundAndNamed() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Bit 8 always reads back as 1, whatever was written. This is the fault the
			//-- original's author commented into his own source to test this test.
			rig.fake.setStuckDataLines(1 << 8, 0);
			MemoryTestResult r = rig.call(() -> rig.tester(ChipSize.WORD)
				.testDataLines(true, ProgressMonitor.NULL));

			assertFalse(r.passed());
			assertEquals(1 << 8, r.stuckHighMask(), "bit 8 was high in every single reading");
			assertEquals(0, r.stuckLowMask());
			assertTrue(r.log().stream().anyMatch(l -> l.contains("permanent \"high\"") && l.contains("8")),
				r.log().toString());
		}
	}

	@Test
	void aDataLineTiedLowIsFoundToo() throws Exception {
		try(Rig rig = new Rig()) {
			rig.fake.setStuckDataLines(0, 1 << 15);
			MemoryTestResult r = rig.call(() -> rig.tester(ChipSize.WORD)
				.testDataLines(true, ProgressMonitor.NULL));

			assertFalse(r.passed());
			assertEquals(1 << 15, r.stuckLowMask());
			assertEquals(0, r.stuckHighMask());
		}
	}

	/**
	 * The point of combining readings across chips rather than trusting one address: a chip that
	 * returns the same value for everything looks exactly like sixteen stuck data lines if you
	 * only look at it.
	 */
	@Test
	void aWorkingMachineIsNotReportedAsStuckJustBecauseOneReadingLooksIt() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Every line moves at some point across the sixteen writes, so nothing is stuck.
			MemoryTestResult r = rig.call(() -> rig.tester(ChipSize.WORD)
				.testDataLines(true, ProgressMonitor.NULL));
			assertFalse(r.hasStuckLines());
			assertEquals(0, r.errorCount());
		}
	}

	@Test
	void aDeadAddressLineShowsUpAsTwoAddressesHoldingOneWord() throws Exception {
		try(Rig rig = new Rig()) {
			//-- Address bit 5 is ignored, so 01000 and 01040 are the same cell. Writing each
			//-- address into itself makes that visible: one of them reads back the other's value.
			rig.fake.setDeadAddressLine(5);
			MemoryTestResult r = rig.call(() -> rig.tester(ChipSize.WORD)
				.testAddressLines(1, ProgressMonitor.NULL));

			assertFalse(r.passed(), "a lost address bit must not pass");
			assertTrue(r.errorCount() > 0);
			//-- The diff is in the address bit that was lost, and the log says so.
			assertTrue(r.log().stream().anyMatch(l -> l.contains("error in address line")), r.log().toString());
		}
	}

	@Test
	void aDeadChipShowsUpInTheDataBitTest() throws Exception {
		try(Rig rig = new Rig()) {
			//-- One data bit dead across the whole range is what a dead chip looks like: it
			//-- supplies that bit for every address it covers.
			rig.fake.setStuckDataLines(0, 1 << 3);
			MemoryTestResult r = rig.call(() -> rig.tester(ChipSize.WORD)
				.testDataBits(1, ProgressMonitor.NULL));

			assertFalse(r.passed());
			assertTrue(r.errorCount() > 0);
			assertTrue(r.log().stream().anyMatch(l -> l.contains("data chip for bit 3")), r.log().toString());
			//-- Every mismatch differs in exactly the dead bit, which is what says it is a chip
			//-- rather than something random.
			for(MemoryTestResult.Mismatch m : r.mismatches()) {
				assertEquals(3, m.lowestDifferingBit(), m.toString());
			}
		}
	}

	@Test
	void theRandomTestCatchesWhatThePatternsMiss() throws Exception {
		try(Rig rig = new Rig()) {
			rig.fake.setStuckDataLines(1 << 2, 0);
			MemoryTestResult r = rig.call(() -> rig.tester(ChipSize.WORD)
				.testRandom(24, new Random(3), ProgressMonitor.NULL));
			assertFalse(r.passed());
			assertTrue(r.errorCount() > 0);
		}
	}

	// ---------------------------------------------------------------------------------------
	// Behaviour
	// ---------------------------------------------------------------------------------------

	@Test
	void cancellingStopsAndSaysSo() throws Exception {
		try(Rig rig = new Rig()) {
			ProgressMonitor stopAtOnce = new ProgressMonitor() {
				private int m_steps;

				@Override
				public void begin(String task, int total) {
				}

				@Override
				public void step(int amount, String note) {
					m_steps += amount;
				}

				@Override
				public boolean isCancelled() {
					return m_steps >= 4;
				}

				@Override
				public void done() {
				}
			};
			MemoryTestResult r = rig.call(() -> rig.tester(ChipSize.WORD).testRandom(64, new Random(1), stopAtOnce));
			assertTrue(r.cancelled());
			assertFalse(r.passed(), "a test that did not finish did not pass");
			assertTrue(r.log().contains("Abort."), r.log().toString());
		}
	}

	@Test
	void theCellsFollowTheTestSoAWindowCanWatch() throws Exception {
		try(Rig rig = new Rig()) {
			rig.call(() -> rig.tester(ChipSize.WORD).testDataBits(1, ProgressMonitor.NULL));
			//-- The grid shows the group, so the test has to leave it holding what it wrote and
			//-- what came back.
			long known = rig.group.getCells().stream().filter(c -> c.getPdpValue().isKnown()).count();
			assertTrue(known > 0, "the group should show what the test read");
		}
	}

	@Test
	void bitNumbersReadTheWayThePascalPrintsThem() {
		assertEquals("4,3,1", MemoryTester.bitNumbers(0x1A));
		assertEquals("", MemoryTester.bitNumbers(0));
		assertEquals(-1, MemoryTester.lowestBitNumber(0));
		assertEquals(3, MemoryTester.lowestBitNumber(0x18));
	}
}
