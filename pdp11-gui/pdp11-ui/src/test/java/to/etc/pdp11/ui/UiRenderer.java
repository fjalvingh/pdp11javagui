package to.etc.pdp11.ui;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lays out and paints a Swing component with no display, and can write the result to a PNG.
 *
 * <p>A {@code JFrame} needs a display; a {@code JPanel} does not. Everything in this application's
 * windows that can actually be got wrong - a layout constraint that does not do what it reads
 * like, a component squeezed to nothing, a status bar that ends up in the middle - lives in
 * lightweight components, and lightweight components can be sized, laid out and painted into an
 * image on a machine with no screen at all.</p>
 *
 * <p>So layout is checked here rather than by putting a window on somebody's desktop and looking
 * at it. That is not only politer, it is the only version that runs on CI.</p>
 *
 * <h2>Why the layout is walked by hand</h2>
 *
 * <p>{@code Container.validate()} does nothing when there is no peer, and a component that is not
 * in a window has no peer. Walking the tree and calling {@code doLayout()} on each container is
 * what {@code validate()} would have done, minus the peer check.</p>
 */
public final class UiRenderer {
	private UiRenderer() {
	}

	/** Size, lay out and paint. The image is exactly the size asked for. */
	public static BufferedImage render(JComponent component, int width, int height) {
		return onEventThread(() -> renderNow(component, width, height));
	}

	private static BufferedImage renderNow(JComponent component, int width, int height) {
		layOutNow(component, width, height);
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			//-- printAll rather than paint: it turns off double buffering for the duration, which
			//-- is what makes an offscreen render come out complete rather than partly blank.
			component.printAll(g);
		} finally {
			g.dispose();
		}
		return image;
	}

	/** Size and lay out, without painting. For tests that only ask where things ended up. */
	public static void layOut(JComponent component, int width, int height) {
		onEventThread(() -> {
			layOutNow(component, width, height);
			return null;
		});
	}

	private static void layOutNow(JComponent component, int width, int height) {
		component.setSize(width, height);
		layoutRecursively(component);
	}

	/**
	 * Everything here runs on the event thread, and that is not a formality.
	 *
	 * <p>Laying out a component tree takes the AWT tree lock and then asks a text component for
	 * its preferred size, which takes that document's read lock. Appending to the same terminal
	 * takes the document's write lock and then revalidates, which wants the tree lock. Do those
	 * two things on two threads and they deadlock - reliably, and in a way that looks like the
	 * renderer hanging rather than like a test doing something it should not.</p>
	 *
	 * <p>That is exactly what happened here the first time, and it is the ordinary Swing rule
	 * rather than anything to do with rendering offscreen: all of it on one thread.</p>
	 */
	private static <T> T onEventThread(java.util.function.Supplier<T> work) {
		if(java.awt.EventQueue.isDispatchThread())
			return work.get();
		java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
			new java.util.concurrent.atomic.AtomicReference<>();
		try {
			java.awt.EventQueue.invokeAndWait(() -> {
				try {
					result.set(work.get());
				} catch(RuntimeException x) {
					failure.set(x);
				}
			});
		} catch(InterruptedException x) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while rendering", x);
		} catch(java.lang.reflect.InvocationTargetException x) {
			throw new IllegalStateException("Rendering failed", x.getCause());
		}
		if(failure.get() != null)
			throw failure.get();
		return result.get();
	}

	private static void layoutRecursively(Component c) {
		if(!(c instanceof Container container))
			return;
		container.doLayout();
		for(Component child : container.getComponents()) {
			layoutRecursively(child);
		}
	}

	/**
	 * Render and write a PNG, for a person to look at.
	 *
	 * <p>The point of this existing rather than only the assertions: a layout can satisfy every
	 * invariant anybody thought to write down and still look wrong, and the only thing that
	 * catches that is a pair of eyes. This puts the picture somewhere they can be used.</p>
	 */
	public static Path renderToFile(JComponent component, int width, int height, Path file) throws IOException {
		BufferedImage image = render(component, width, height);
		//-- The write itself is ordinary file I/O and does not belong on the event thread.
		Path dir = file.getParent();
		if(dir != null)
			Files.createDirectories(dir);
		ImageIO.write(image, "png", file.toFile());
		return file;
	}

	/**
	 * Install the look and feel the application uses, so a render looks like the application.
	 *
	 * @return whether it worked; the cross-platform default is used if it did not, and a layout
	 *         checked against that is still worth checking
	 */
	public static boolean installLookAndFeel() {
		try {
			//-- The same one the application installs, or a render is a picture of something
			//-- nobody will ever see.
			UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarculaLaf());
			return true;
		} catch(Exception | LinkageError x) {
			return false;
		}
	}
}
