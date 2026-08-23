package to.etc.pdp11.ui.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The storage key, both ways round.
 *
 * <p>It only had to go one way until the window layout started coming back: nothing read the
 * {@code visible} flag the settings file had been recording since the beginning, so nothing ever
 * had to turn {@code "MEMORY:3"} back into a key.</p>
 */
class WindowKeyTest {
	@Test
	void aStorageKeySurvivesTheRoundTrip() {
		for(WindowType t : WindowType.values()) {
			WindowKey single = WindowKey.of(t);
			assertEquals(single, WindowKey.fromStorageKey(single.toStorageKey()), t.toString());
			WindowKey instance = WindowKey.of(t, "3");
			assertEquals(instance, WindowKey.fromStorageKey(instance.toStorageKey()), t.toString());
		}
	}

	@Test
	void aRegisterGroupKeepsItsNameEvenWithPunctuationInIt() {
		//-- The instance id of a register group window is the group's name out of the machine
		//-- description, which is not something this code gets to choose.
		WindowKey k = WindowKey.of(WindowType.REGISTER_GROUP, "Bits.RL11:CS");
		assertEquals("Bits.RL11:CS", WindowKey.fromStorageKey(k.toStorageKey()).instanceId(),
			"only the first colon separates the type from the instance");
	}

	/**
	 * Nothing in settings may stop the application starting (CLAUDE.md), and a settings file can
	 * name a window type this version no longer has - {@code TERMINAL} was one until it was
	 * removed - or be hand-edited into anything at all.
	 */
	@Test
	void anUnreadableKeyIsNullRatherThanAnException() {
		assertNull(WindowKey.fromStorageKey("TERMINAL"));
		assertNull(WindowKey.fromStorageKey("NOT_A_WINDOW:1"));
		assertNull(WindowKey.fromStorageKey(""));
		assertNull(WindowKey.fromStorageKey(null));
		assertNull(WindowKey.fromStorageKey("memory"), "the enum name, not a caption");
	}
}
