package to.etc.pdp11.core.machine;

/** A machine description could not be preprocessed. */
public class M4Exception extends RuntimeException {
	public M4Exception(String message) {
		super(message);
	}

	public M4Exception(String message, Throwable cause) {
		super(message, cause);
	}
}
