package to.etc.pdp11.core.console;

/**
 * What a {@link ConsoleConnection}'s reader thread hands its bytes to.
 *
 * <p>{@link AbstractConsole} implements it. It exists as its own interface so the connection
 * can be tested - and reasoned about - without a console, and so that the reader thread's
 * contract is stated in one place instead of being implied by a field's type.</p>
 *
 * <p>Everything here is called on the reader thread, one call at a time.</p>
 */
public interface SerialReceiver {
	/**
	 * Bytes arrived, already masked to 7 bits ({@code SerialIoHubU.pas:843}) and turned into
	 * chars one for one.
	 */
	void onSerialReceive(String data);

	/** The transport reached end of stream or failed. No more bytes will arrive. */
	void onDisconnected(Throwable cause);
}
