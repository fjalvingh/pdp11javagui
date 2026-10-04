package to.etc.pdp11.common.diag.media;

/**
 * A file is not the kind of medium it was taken for, or is damaged past reading.
 *
 * <p>Checked, because every caller has something better to do than die: the collector notes it
 * against the download and goes on to the next one.</p>
 */
public class MediaFormatException extends Exception {
	public MediaFormatException(String message) {
		super(message);
	}
}
