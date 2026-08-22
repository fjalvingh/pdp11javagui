package to.etc.pdp11.ui.settings;

import java.awt.Rectangle;
import java.util.List;

/**
 * Where a window was, and whether it was showing.
 *
 * <p>Replaces {@code JH_Utilities.pas:1718-1767}, which stores
 * {@code <FormName>.Left/.Top/.Width/.Height/.Visible/.WindowState} in the registry. Two things
 * change. The coordinates are <b>screen</b> coordinates rather than MDI-client-relative ones,
 * because there is no MDI client any more - which is also why PLAN.md §4 says not to migrate the
 * old settings: those numbers mean nothing here. And the key is a {@code WindowKey} rather than a
 * form name, so the windows that come in unlimited numbers can be told apart.</p>
 *
 * <p>The Pascal also restores <i>size</i> only when the form's {@code BorderStyle} is sizeable;
 * that rule goes, because every window in this UI is resizable.</p>
 */
public record WindowGeometry(int x, int y, int width, int height, boolean visible, boolean maximized) {
	/** Smallest window worth restoring; anything less is a corrupt or hand-edited file. */
	public static final int MIN_SIZE = 80;

	/** How much of a window has to be on a screen for it to count as reachable. */
	private static final int MIN_VISIBLE = 40;

	public Rectangle bounds() {
		return new Rectangle(x, y, width, height);
	}

	public static WindowGeometry of(Rectangle bounds, boolean visible, boolean maximized) {
		return new WindowGeometry(bounds.x, bounds.y, bounds.width, bounds.height, visible, maximized);
	}

	public WindowGeometry withVisible(boolean nowVisible) {
		return new WindowGeometry(x, y, width, height, nowVisible, maximized);
	}

	/** Whether this is worth restoring at all. */
	public boolean isUsable() {
		return width >= MIN_SIZE && height >= MIN_SIZE;
	}

	/**
	 * Move this window back onto a screen if it is no longer on one.
	 *
	 * <p><b>This does not exist in the Pascal, and it is the reason to write this out rather than
	 * store four numbers and trust them.</b> Saved bounds outlive the arrangement they were saved
	 * under: unplug the second monitor, or dock a laptop somewhere else, and a window restored
	 * where it used to be is a window the user cannot reach and cannot close. PLAN.md §3 calls
	 * for exactly this check.</p>
	 *
	 * <p>A window counts as reachable if a corner's worth of it - {@value #MIN_VISIBLE} pixels
	 * each way - lands on some screen, which is enough to grab its title bar with. One that does
	 * not is moved onto the first screen given, shrunk first if it is larger than that screen.</p>
	 *
	 * @param screens the bounds of the available screens, in the order the primary comes first
	 * @return this geometry if it is reachable, or one that is
	 */
	public WindowGeometry clampTo(List<Rectangle> screens) {
		if(screens.isEmpty())
			return this;                                    // nothing to clamp against; leave it alone
		Rectangle mine = bounds();
		for(Rectangle screen : screens) {
			Rectangle overlap = screen.intersection(mine);
			if(!overlap.isEmpty() && overlap.width >= MIN_VISIBLE && overlap.height >= MIN_VISIBLE)
				return this;
		}
		Rectangle primary = screens.get(0);
		int w = Math.min(width, primary.width);
		int h = Math.min(height, primary.height);
		//-- Centred rather than at the corner: a window that lost its screen is already a
		//-- surprise, and putting it where the user is looking softens that.
		int nx = primary.x + Math.max(0, (primary.width - w) / 2);
		int ny = primary.y + Math.max(0, (primary.height - h) / 2);
		return new WindowGeometry(nx, ny, w, h, visible, maximized);
	}
}
