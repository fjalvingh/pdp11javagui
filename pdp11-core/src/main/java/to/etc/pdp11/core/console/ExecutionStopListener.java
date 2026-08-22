package to.etc.pdp11.core.console;

import to.etc.pdp11.core.addr.Address;

/**
 * Told when the machine stops - whether because it was asked to, or because the program ran
 * into a HALT on its own.
 *
 * <p>Ported from {@code TConsoleCPUStopEvent} ({@code ConsoleGenericU.pas:164}). The Pascal
 * fires this from a 100 ms timer, deliberately outside any command sequence
 * ({@code :511-531}), so that the handler is free to issue new console commands immediately -
 * which is exactly what the execution-control window does with the new PC.</p>
 *
 * <p>Here there is no timer. The event is posted as a task onto the same single-threaded
 * command executor, which gives that ordering guarantee for free: a stop event can never
 * interleave with a command, and a handler may enqueue more commands safely. See PLAN.md
 * §1.</p>
 */
@FunctionalInterface
public interface ExecutionStopListener {
	/**
	 * @param pc where the machine stopped, as a virtual address - that is what a console
	 *           reports.
	 */
	void onExecutionStop(Console console, Address pc);
}
