package to.etc.pdp11.core.util;

import java.util.Locale;

/**
 * Where {@code pdp11-core} writes diagnostics.
 *
 * <p>This interface exists to break a coupling, not to abstract for its own sake. In the
 * Pascal, {@code BitFieldU}, {@code MemoryLoaderU} and {@code MediaImageDevicesU} all
 * {@code uses FormMainU} solely to reach four global {@code Log*} procedures
 * ({@code FormMainU.pas:314-345}) which forward to {@code FormMain.FormLog} - so even the
 * units with no other UI involvement drag in the main form. Nothing like that can follow into
 * a module that must not depend on Swing.</p>
 *
 * <p>The Log window becomes one implementation of this; {@link #NULL} is the other, and tests
 * use it or a recording one.</p>
 */
public interface Logger {
	/**
	 * Whether anything is listening on this channel. Byte-level tracing calls this before
	 * building a message, because on a serial link it runs per byte.
	 */
	boolean isEnabled(LogChannel channel);

	void log(LogChannel channel, String message);

	default void log(String message) {
		log(LogChannel.OTHER, message);
	}

	/**
	 * Formatted logging. The format is only applied when the channel is enabled, so an
	 * expensive message costs nothing when its column is switched off.
	 */
	default void log(LogChannel channel, String format, Object... args) {
		if(isEnabled(channel))
			log(channel, String.format(Locale.ROOT, format, args));
	}

	default void log(String format, Object... args) {
		log(LogChannel.OTHER, format, args);
	}

	/**
	 * Discards everything. The default for code that has no logger handed to it, so that a
	 * missing logger is never a null check.
	 */
	Logger NULL = new Logger() {
		@Override
		public boolean isEnabled(LogChannel channel) {
			return false;
		}

		@Override
		public void log(LogChannel channel, String message) {
		}

		@Override
		public String toString() {
			return "Logger.NULL";
		}
	};
}
