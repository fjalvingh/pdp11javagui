package to.etc.pdp11.core.conn;

import to.etc.pdp11.core.console.ConsoleException;

/**
 * A connection attempt gave up because a newer one took over.
 *
 * <p>Not a failure: nothing about the machine went wrong, and the connection the user actually
 * asked for is the one that superseded this attempt. It is thrown rather than returned quietly
 * because {@link ConnectionManager#connect} otherwise promises a live console on return - and
 * it is a type of its own so that the caller can tell "somebody pressed Connect twice" apart
 * from "the machine did not answer" and say nothing about it.</p>
 */
public final class ConnectionSupersededException extends ConsoleException {
	public ConnectionSupersededException() {
		super("The connection attempt was superseded by a newer one");
	}
}
