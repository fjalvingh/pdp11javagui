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
	 * <p>The instance id is the lowest one no window <i>exists</i> for, which is not the same as
	 * the lowest one not on the screen: closing a tool window hides it, and a hidden window keeps
	 * its id because it keeps its contents and its saved geometry is keyed on it. Ids come back
	 * only when a window is really disposed of, by {@link #closeAll(WindowType)} or on the way
	 * out.</p>
	 *
	 * <p>Which is why {@link #hiddenWindows()} exists: a hidden window that the menu did not list
	 * was a window nothing could ever get back, holding a range, its edits and a
	 * {@code MemoryCellGroup} on the propagation bus until shutdown.</p>
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

	/**
	 * Every window that exists but is not showing, in the order they were first opened.
	 *
	 * <p>These are recoverable and have to be listed somewhere, or they are not. A singleton
	 * comes back through its own entry in the Windows menu whatever this says; a memory view has
	 * no such entry - the menu offers "New memory window", which builds a different one - so
	 * without this the only way back is this list.</p>
	 */
	public List<ToolWindow> hiddenWindows() {
		List<ToolWindow> l = new ArrayList<>();
		for(ToolWindow w : m_windows.values()) {
			if(!w.isVisible())
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

	/** The other half of {@link #hideAll()}, which PLAN.md §3 asks for and which was missing. */
	public void showAll() {
		for(ToolWindow w : new ArrayList<>(m_windows.values())) {
			if(!w.isVisible())
				w.showWindow();
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
		applyGeometry(w, w.key().toStorageKey());
	}

	/**
	 * The same, for a frame that is not a tool window.
	 *
	 * <p>Which is the main window, and only the main window. It had no geometry persistence at
	 * all: every tool window came back where it was left and the frame they all sit in came back
	 * wherever the platform felt like putting it.</p>
	 */
	public void applyGeometry(JFrame w, String storageKey) {
		WindowGeometry saved = m_settingsStore.get().getWindowGeometry(storageKey);
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
		rememberGeometry(w, w.key().toStorageKey());
	}

	/** The same, for a frame that is not a tool window - see {@link #applyGeometry(JFrame, String)}. */
	public void rememberGeometry(JFrame w, String storageKey) {
		boolean maximized = (w.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
		//-- A maximized window's getBounds() is the screen, which is no use as a restore size.
		//-- What is wanted is where it would go back to, and Swing does not keep that - so a
		//-- maximized window keeps whatever bounds were last recorded for it.
		WindowGeometry previous = m_settingsStore.get().getWindowGeometry(storageKey);
		WindowGeometry now = maximized && previous != null
			? new WindowGeometry(previous.x(), previous.y(), previous.width(), previous.height(),
				w.isVisible(), true)
			: WindowGeometry.of(w.getBounds(), w.isVisible(), maximized);
		m_settingsStore.get().setWindowGeometry(storageKey, now);
	}

	// -------------------------------------------------------------------------------------
	// Restoring the layout
	// -------------------------------------------------------------------------------------

	/** Where the main window's geometry is filed. It is not a {@link ToolWindow} and has no key. */
	public static final String MAIN_WINDOW_KEY = "MAIN";

	/**
	 * Reopen every window the settings file says was open when the application last shut down.
	 *
	 * <p>The geometry record has carried a {@code visible} flag since the beginning and nothing
	 * ever read it: every launch opened the main window alone, while the settings file recorded
	 * in detail the layout it was not restoring. The Delphi original restores it
	 * ({@code JH_Utilities.pas:1718-1767} stores {@code .Visible} and puts it back), and a
	 * window layout is exactly the kind of thing worth not having to rebuild every morning.</p>
	 *
	 * <p><b>Every failure here is one window skipped, never a failed startup.</b> A saved entry
	 * can name a window type this version no longer has, or a register group the currently
	 * loaded machine description does not declare - the factory throws for that one on purpose -
	 * and CLAUDE.md's rule is that nothing in settings may stop the application starting.</p>
	 *
	 * @return how many windows were reopened
	 */
	public int restoreVisibleWindows() {
		int opened = 0;
		//-- A copy: opening a window writes geometry back into the same map.
		for(Map.Entry<String, WindowGeometry> e : new ArrayList<>(m_settingsStore.get().windows().entrySet())) {
			if(MAIN_WINDOW_KEY.equals(e.getKey()))
				continue;
			WindowGeometry g = e.getValue();
			if(g == null || !g.visible())
				continue;
			WindowKey key = WindowKey.fromStorageKey(e.getKey());
			if(key == null || !isRegistered(key.type()))
				continue;
			try {
				open(key);
				opened++;
			} catch(RuntimeException x) {
				log("Could not reopen " + e.getKey() + ": " + x.getMessage());
			}
		}
		return opened;
	}

	private void log(String message) {
		AppContext ctx = m_context;
		if(ctx != null)
			ctx.getLogger().log(to.etc.pdp11.core.util.LogChannel.OTHER, message);
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
