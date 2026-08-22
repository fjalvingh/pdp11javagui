package to.etc.pdp11.core.fake;

/**
 * The simulated console noticed something wrong - an odd address, a nonexistent location, a
 * character where one does not belong.
 *
 * <p>Ported from {@code EFakePDP11Error} ({@code FakePDP11GenericU.pas:53}), which is raised
 * with an empty message throughout because nothing ever reads it: the ODT state machine
 * catches it and prints {@code "?"}. It carries a message here anyway, since a test that fails
 * is much easier to read when it says which rule fired.</p>
 */
public class FakePdp11Exception extends RuntimeException {
	public FakePdp11Exception(String message) {
		super(message);
	}
}
