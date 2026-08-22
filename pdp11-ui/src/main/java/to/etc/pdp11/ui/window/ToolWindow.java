package to.etc.pdp11.ui.window;

import to.etc.pdp11.ui.AppContext;

import javax.swing.JFrame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A free-floating top-level window.
 *
 * <p>Ported from {@code TFormChild} ({@code FormChildU.pas}), which is deliberately thin there
 * and thinner here. It only ever provided geometry persistence, hide-without-destroy, and two
 * hooks - and hide-without-destroy is now {@code setVisible(false)}, because these are real
 * windows.</p>
 *
 * <h2>What goes away with the MDI model</h2>
 *
 * <p>The Pascal hides a window by flipping {@code FormStyle} between {@code fsMDIChild} and
 * {@code fsNormal} ({@code FormChildU.pas:92-98}), which destroys and recreates the native
 * handle. That one choice is the root of a Qt5 segfault workaround
 * ({@code FormMainU.pas:1037-1056}), an {@code OnShow}-suppression hack
 * ({@code FormChildU.pas:95-97}), and the assembler windows clearing their editors on hide
 * ({@code FormMacro11SourceU.pas:187-192}). All four are gone, and with them a deliberate
 * behaviour change worth knowing about: <b>a window's contents now survive being hidden</b>,
 * where before they were re-read from disk on every reopen.</p>
 *
 * <p>Two Pascal bugs not reproduced, both noted in PLAN.md §3: several subclasses declare
 * {@code constructor Create} <i>without</i> {@code override}, and {@code TControl.Show}/
 * {@code Hide} are not virtual in the LCL, so {@code TFormChild.Show} shadows rather than
 * overrides them - the current code works by luck.</p>
 */
public abstract class ToolWindow extends JFrame {
	private final WindowKey m_key;

	private final AppContext m_context;

	private boolean m_shownBefore;

	protected ToolWindow(WindowKey key, AppContext context) {
		super(key.title());
		m_key = key;
		m_context = context;
		//-- Closing a tool window hides it. Only the main window quits the application, and only
		//-- the window manager disposes of these - a closed window that is reopened should come
		//-- back as it was, which is what "hide" means and "dispose" does not.
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				hideWindow();
			}
		});
	}

	public final WindowKey key() {
		return m_key;
	}

	protected final AppContext context() {
		return m_context;
	}

	/**
	 * Called once, the first time this window is shown.
	 *
	 * <p>Replaces {@code OnAfterShow} ({@code FormChildU.pas:53-113}), which exists there because
	 * the {@code FormStyle} flip fires {@code OnShow} at times the form did not expect. Here it
	 * is simply the natural place for work that is only worth doing if the window is ever
	 * looked at.</p>
	 */
	protected void onFirstShow() {
	}

	/** Called as the window goes away. Replaces {@code OnBeforeHide}. */
	protected void onHiding() {
	}

	/** Show, restoring geometry the first time and running {@link #onFirstShow()} once. */
	public final void showWindow() {
		boolean first = !m_shownBefore;
		if(first) {
			m_shownBefore = true;
			onFirstShow();
		}
		setVisible(true);
		toFront();
		requestFocus();
	}

	public final void hideWindow() {
		onHiding();
		//-- Geometry is saved on the way out rather than on every move: a window being dragged
		//-- generates a component event per pixel, and none of them are worth a file write.
		m_context.getWindowManager().rememberGeometry(this);
		setVisible(false);
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + m_key + "]";
	}
}
