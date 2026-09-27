package to.etc.pdp11.ui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The application's icon, which replaces the Java cup every frame shows by default.
 *
 * <p>It is the original PDP11GUI's own icon, {@code Pdp11gui/pdp11GUI.ico}, with each of the seven
 * sizes in that file extracted to a PNG. All of them are handed to the window system, which picks
 * the one that fits where it is drawing - a 16 pixel title bar, a 48 pixel task switcher -
 * rather than scaling one image badly.</p>
 *
 * <p>Set on every frame, the main window and each tool window, because on X11 the icon is a
 * property of the window and not of the application: a frame without one shows the cup. Dialogs
 * need nothing; they take their owner's.</p>
 *
 * <p>An icon that cannot be read is not a reason to stop starting, so a missing or damaged image
 * is left out and said on stderr, and with none at all the frames keep the default.</p>
 */
public final class AppIcon {
	private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 256};

	private static final List<Image> IMAGES = load();

	private AppIcon() {
	}

	/** Every size there is, smallest first; empty if none could be read. */
	public static List<Image> images() {
		return IMAGES;
	}

	/**
	 * Put it where the platform shows an application rather than a window: the macOS Dock. Most
	 * platforms have no such place, or take it from the windows, and are left alone.
	 */
	public static void installOnTaskbar() {
		if(IMAGES.isEmpty() || !Taskbar.isTaskbarSupported())
			return;
		Taskbar taskbar = Taskbar.getTaskbar();
		if(!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE))
			return;
		try {
			taskbar.setIconImage(IMAGES.get(IMAGES.size() - 1));
		} catch(UnsupportedOperationException | SecurityException x) {
			System.err.println("Could not set the application icon: " + x);
		}
	}

	private static List<Image> load() {
		List<Image> out = new ArrayList<>();
		for(int size : SIZES) {
			String name = "icon/pdp11gui-" + size + ".png";
			try(InputStream is = AppIcon.class.getResourceAsStream(name)) {
				if(is == null) {
					System.err.println("The application icon " + name + " is not packaged");
					continue;
				}
				Image image = ImageIO.read(is);
				if(image == null)
					System.err.println("The application icon " + name + " is not an image");
				else
					out.add(image);
			} catch(IOException x) {
				System.err.println("Could not read the application icon " + name + ": " + x);
			}
		}
		return List.copyOf(out);
	}
}
