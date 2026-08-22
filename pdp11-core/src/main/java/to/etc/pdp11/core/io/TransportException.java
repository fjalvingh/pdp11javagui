package to.etc.pdp11.core.io;

import java.io.IOException;

/** A transport could not be opened, or failed in a way that is not going to recover. */
public class TransportException extends IOException {
	public TransportException(String message) {
		super(message);
	}

	public TransportException(String message, Throwable cause) {
		super(message, cause);
	}
}
