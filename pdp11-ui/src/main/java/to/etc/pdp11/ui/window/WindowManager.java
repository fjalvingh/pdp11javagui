package to.etc.pdp11.ui.window;

import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.settings.SettingsStore;
import to.etc.pdp11.ui.settings.WindowGeometry;

import javax.swing.JFrame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Creates windows on demand, raises the ones that exist, and remembers where they were.
 *
 * <p>Replaces {@code TFormMain}'s window handling, which creates all ~26 windows eagerly in
 * {@code FormCreate} ({@code FormMainU.pas:349-521}), never destroys them, and finds them again
 * by matching captions ({@code ChildFormByCaption}, {@code :993-1005}). Here they are created the
 * first time they are asked for and found by {@link WindowKey}.</p>
 *
 * <h2>What the MDI menu becomes</h2>
 *
 * <p>Cascade, ArrangeIcons, MinimizeAll, MinimizeAllButActive and RestoreAll all go
 * ({@code FormMainU.pas:937-988}) - along with the fixed {@code array[0..100] of TPoint} in
 * Cascade that silently overflows, and the missing nil check on {@code ActiveMDIChild} at
 * {@code :986}. What replaces them is a live list of open windows built from
 * {@link #openWindows()} when the menu opens, which also retires the <b>100 ms timer</b> that
 * exists today only to keep menu checkmarks in step with window visibility
 * ({@code UpdateGUI}, {@code :1111-1133}).</p>
 */
public final class WindowManager {
	private final SettingsStore m_settingsStore;

	/** Insertion-ordered, so the Windows menu lists windows in the order they were opened. */
	private final Map<WindowKey, ToolWindow> m_windows = new LinkedHashMap<>();

	private final Map<WindowType, Function<WindowKey, ToolWindow>> m_factories = new LinkedHashMap<>();

	private AppContext m_context;

	public WindowManager(SettingsStore settingsStore) {
		m_settingsStore = settingsStore;
	}

	/** Set once, immediately after construction; see {@link AppContext#create}. */
	public void setContext(AppContext context) {
		m_context = context;
	}

	/**
	 * Say how to build a kind of window.
	 *
	 * <p>Registered rather than switched on, so that this class knows about no window in
	 * particular - which is what lets the windows depend on it instead of the other way
	 * round.</p>
	 */
	public void register(WindowType type, Function<WindowKey, ToolWindow> factory) {
		m_factories.put(type, factory);
	}

	public boolean isRegistered(WindowType type) {
		return m_factories.containsKey(type);
	}

	/** Create it if it does not exist, then show and raise it. */
	public ToolWindow open(WindowKey key) {
		ToolWindow w = m_windows.get(key);
		if(w == null) {
			Function<WindowKey, ToolWindow> factory = m_factories.get(key.type());
			if(factory == null)
				throw new IllegalStateException("No window is registered for " + key.type());
			w = factory.apply(key);
			m_windows.put(key, w);
			applyGeometry(w);
		}
		w.showWindow();
		return w;
	}

	public ToolWindow open(WindowType type) {
		return open(WindowKey.of(type));
	}

	/**
	 * Open another window of a type there can be several of.
	 *
	 * <p>The instance id is the lowest number not in use, so closing the second of three and
	 * asking for another gets the gap back rather than counting ever upwards - which matters
	 * because the id is what the saved geometry is keyed on.</p>
	 */
	public ToolWindow openNew(WindowType type) {
		for(int i = 1; ; i++) {
			WindowKey key = WindowKey.of(type, String.valueOf(i));
			if(!m_windows.containsKey(key))
				return open(key);
		}
	}

	/** Every window of one type that exists, showing or not. */
	public List<ToolWindow> windowsOfType(WindowType type) {
		List<ToolWindow> l = new ArrayList<>();
		for(ToolWindow w : m_windows.values()) {
			if(w.key().type() == type)
				l.add(w);
		}
		return l;
	}

	/** Bring an existing window forward without creating one. Does nothing if it does not exist. */
	public void raise(WindowKey key) {
		ToolWindow w = m_windows.get(key);
		if(w == null)
			return;
		if(!w.isVisible())
			w.showWindow();
		if(w.getState() == JFrame.ICONIFIED)
			w.setState(JFrame.NORMAL);
		w.toFront();
		w.requestFocus();
	}

	/** The window for a key if it has been created, or {@code null}. */
	public ToolWindow find(WindowKey key) {
		return m_windows.get(key);
	}

	/** Every window that exists and is showing, in the order they were first opened. */
	public List<ToolWindow> openWindows() {
		List<ToolWindow> l = new ArrayList<>();
		for(ToolWindow w : m_windows.values()) {
			if(w.isVisible())
				l.add(w);
		}
		return l;
	}

	/** Every window that has been created, showing or not. */
	public List<ToolWindow> allWindows() {
		return new ArrayList<>(m_windows.values());
	}

	public void hideAll() {
		for(ToolWindow w : new ArrayList<>(m_windows.values())) {
			if(w.isVisible())
				w.hideWindow();
		}
	}

	/** Remember where everything is, then dispose of it. For shutting down. */
	public void closeAll() {
		for(ToolWindow w : new ArrayList<>(m_windows.values())) {
			rememberGeometry(w);
			w.dispose();
		}
		m_windows.clear();
	}

	/**
	 * Drop the windows created for one thing - the register-group windows a machine description
	 * made, when a different description is loaded.
	 *
	 * <p>{@code UnloadMachineDescription} ({@code FormMainU.pas:648-675}) does the same job.</p>
	 */
	public void closeAll(WindowType type) {
		for(ToolWindow w : new ArrayList<>(m_windows.values())) {
			if(w.key().type() == type) {
				rememberGeometry(w);
				w.dispose();
				m_windows.remove(w.key());
			}
		}
	}

	// -------------------------------------------------------------------------------------
	// Geometry
	// -------------------------------------------------------------------------------------

	/**
	 * Put a newly created window where it was last time, if that is still a place.
	 *
	 * <p>A window with nothing saved, or something unusable saved, is left to
	 * {@code setLocationByPlatform} - which cascades new windows sensibly and is the one thing
	 * the discarded MDI Cascade command was actually for.</p>
	 */
	private void applyGeometry(ToolWindow w) {
		WindowGeometry saved = m_settingsStore.get().getWindowGeometry(w.key().toStorageKey());
		if(saved == null || !saved.isUsable()) {
			w.setLocationByPlatform(true);
			return;
		}
		WindowGeometry usable = saved.clampTo(screenBounds());
		w.setBounds(usable.bounds());
		if(usable.maximized())
			w.setExtendedState(JFrame.MAXIMIZED_BOTH);
	}

	/** Note where a window is now, so the next run can put it back. */
	public void rememberGeometry(ToolWindow w) {
		boolean maximized = (w.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
		//-- A maximized window's getBounds() is the screen, which is no use as a restore size.
		//-- What is wanted is where it would go back to, and Swing does not keep that - so a
		//-- maximized window keeps whatever bounds were last recorded for it.
		WindowGeometry previous = m_settingsStore.get().getWindowGeometry(w.key().toStorageKey());
		WindowGeometry now = maximized && previous != null
			? new WindowGeometry(previous.x(), previous.y(), previous.width(), previous.height(),
				w.isVisible(), true)
			: WindowGeometry.of(w.getBounds(), w.isVisible(), maximized);
		m_settingsStore.get().setWindowGeometry(w.key().toStorageKey(), now);
	}

	/** Note where everything is. Called on the way out. */
	public void rememberAllGeometry() {
		for(ToolWindow w : m_windows.values()) {
			rememberGeometry(w);
		}
	}

	/**
	 * The screens available now.
	 *
	 * <p>Primary first, because that is where {@link WindowGeometry#clampTo} puts a window that
	 * has lost the screen it was on.</p>
	 */
	public static List<Rectangle> screenBounds() {
		List<Rectangle> l = new ArrayList<>();
		if(GraphicsEnvironment.isHeadless())
			return l;
		GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
		GraphicsDevice primary = env.getDefaultScreenDevice();
		l.add(primary.getDefaultConfiguration().getBounds());
		for(GraphicsDevice d : env.getScreenDevices()) {
			if(d != primary)
				l.add(d.getDefaultConfiguration().getBounds());
		}
		return l;
	}

	AppContext context() {
		return m_context;
	}
}
