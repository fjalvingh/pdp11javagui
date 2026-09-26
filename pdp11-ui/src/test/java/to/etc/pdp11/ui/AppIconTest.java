package to.etc.pdp11.ui;

import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The icon is packaged and readable, in every size the original's {@code .ico} carried.
 *
 * <p>Headless: reading a PNG needs no display. What would go wrong here goes wrong silently at
 * run time - a frame with no icon just shows the Java cup again - so it is checked here.</p>
 */
class AppIconTest {
	@Test
	void everySizeIsPackagedAndSquare() {
		List<Image> images = AppIcon.images();
		assertEquals(List.of(16, 20, 24, 32, 40, 48, 256), images.stream().map(i -> i.getWidth(null)).toList());
		for(Image i : images)
			assertEquals(i.getWidth(null), i.getHeight(null));
	}
}
