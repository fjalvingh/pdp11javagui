package to.etc.pdp11.core.console;

/**
 * What every console test rig has to do before a test can watch for a stop.
 *
 * <p>Here rather than copied into each rig because it has now been got wrong twice, in the same
 * way, in two test classes written months apart - and the third console to be given a fake will
 * be written by somebody who has never heard of it.</p>
 */
final class ConsoleRigs {
	private ConsoleRigs() {
	}

	/**
	 * Swallow the stop event the machine's own power-on left lying about.
	 *
	 * <p>A fake is powered on before anything connects to it, so the first thing the console reads
	 * is the halt report that reset printed - and it is right to decode that as a stop: a real
	 * machine sitting at its prompt has genuinely stopped, and PDP11GUI says where the moment it
	 * connects. But it happened before the test did anything, and a test that installs a stop
	 * listener afterwards must not be handed it as though it were the stop <i>it</i> caused.</p>
	 *
	 * <p>The event is decoded on the reader thread and queued on the command thread by
	 * {@code AbstractConsole.signalExecutionStop} <i>before</i> the prompt that ends {@code init}
	 * is published, so by the time {@code init} returns it is already in the queue - and the queue
	 * is FIFO and one thread deep. Running an empty command therefore cannot return until the
	 * stale stop task has run, with no listener installed yet, which is where it should go.</p>
	 *
	 * <p>Without this the two threads race: the command thread delivering the stale event against
	 * the test thread installing its listener. It is not theoretical, and it does not fail where
	 * it is written - both times it has been seen, it was a CI machine that lost the race. The
	 * V3.40C fake's reset PC (0165714) arrived as the answer to a single step expecting 01002, and
	 * the ODT fake's power-up PC (0173000, the boot ROM, which is where an 11/73 really comes up)
	 * arrived as the answer to a reset expecting 04000.</p>
	 */
	static void drainPowerOnStop(ConsoleConnection connection) throws ConsoleException {
		connection.run(() -> {
		});
	}
}
