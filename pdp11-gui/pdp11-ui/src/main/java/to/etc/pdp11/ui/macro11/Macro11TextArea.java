package to.etc.pdp11.ui.macro11;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import java.awt.Graphics;
import java.awt.image.BufferedImage;

/**
 * An {@link RSyntaxTextArea} that can be painted with no display.
 *
 * <p>One override, for one bug. {@code RSyntaxTextArea.paintComponent} refreshes its font metrics
 * on the first paint and gets the {@code Graphics} for that from {@code getGraphics()} - the
 * <i>component's own</i>, rather than the one it was handed to paint into. A component that is
 * not in a displayable window has none, so that returns null and the first paint throws
 * {@code NullPointerException} inside the library.</p>
 *
 * <p>Which matters here because of the project rule that every layout is checked headlessly:
 * {@code UiRenderer} lays a panel out and paints it into an image on a machine with no screen,
 * and CI has no screen at all. Without this, the two editor tabs of the Assembler window are the
 * only part of the application that cannot be looked at without borrowing somebody's desktop.</p>
 *
 * <p>So a component with no {@code Graphics} of its own is given one from a scratch image. It is
 * used for nothing but measuring a font, the metrics it yields are the same ones the real
 * surface would give, and a fresh one is handed out each time because the caller disposes it.</p>
 */
final class Macro11TextArea extends RSyntaxTextArea {
	/** One pixel is enough: nothing is ever drawn on this, only measured against it. */
	private static final BufferedImage SCRATCH = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

	@Override
	public Graphics getGraphics() {
		Graphics g = super.getGraphics();
		return g != null ? g : SCRATCH.createGraphics();
	}
}
