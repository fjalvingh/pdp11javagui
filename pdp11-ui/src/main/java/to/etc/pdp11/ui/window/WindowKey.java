package to.etc.pdp11.ui.window;

/**
 * Identifies one window: its type, and which one of that type.
 *
 * <p>PLAN.md §3. The {@code instanceId} covers the two dynamic cases uniformly - memory views,
 * of which there may be any number ({@code MEMORY/"3"}), and the register-group windows the
 * machine description creates, one per group ({@code REGISTER_GROUP/"MMU"}). The latter already
 * exist in the Pascal: {@code LoadMachineDescription} ({@code FormMainU.pas:608-645}) builds a
 * window and a menu item per group and frees them again in {@code UnloadMachineDescription}
 * ({@code :648-675}), so dynamic windows are a requirement whatever else changes.</p>
 *
 * @param type       what kind of window
 * @param instanceId which one, or {@code ""} for the types there is only ever one of
 */
public record WindowKey(WindowType type, String instanceId) {
	public WindowKey {
		if(type == null)
			throw new IllegalArgumentException("A window key needs a type");
		if(instanceId == null)
			instanceId = "";
	}

	/** The single window of a type. */
	public static WindowKey of(WindowType type) {
		return new WindowKey(type, "");
	}

	public static WindowKey of(WindowType type, String instanceId) {
		return new WindowKey(type, instanceId);
	}

	public boolean isSingleton() {
		return instanceId.isEmpty();
	}

	/** The window's title: the type's, with the instance named after it when there is one. */
	public String title() {
		return isSingleton() ? type.getTitle() : type.getTitle() + " - " + instanceId;
	}

	/**
	 * How this key appears in the settings file.
	 *
	 * <p>Stable across renames of anything the user can see, which is the whole point of not
	 * keying on the caption.</p>
	 */
	public String toStorageKey() {
		return isSingleton() ? type.name() : type.name() + ":" + instanceId;
	}

	@Override
	public String toString() {
		return toStorageKey();
	}
}
