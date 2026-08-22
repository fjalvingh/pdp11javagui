package to.etc.pdp11.ui.numbers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.util.NumberConverter.Base;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.UiRenderer;

import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The converter window: three fields that update each other, and do not update each other
 * forever.
 *
 * <p>{@code NumberConverterTest} covers what the conversions say. What is here is the part that
 * only a window can get wrong - the loop between the fields, the caret in the field being typed
 * in, and what happens when a value stops fitting.</p>
 */
class NumberConverterPanelTest {
	private static final int WIDTH = 560;

	private static final int HEIGHT = 340;

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static NumberConverterPanel panel() {
		return Edt.call(NumberConverterPanel::new);
	}

	private static String text(NumberConverterPanel panel, Base base) {
		return Edt.call(() -> panel.getField(base).getText());
	}

	/** Type, one character at a time, the way a person does. */
	private static void type(NumberConverterPanel panel, Base base, String characters) {
		Edt.run(() -> {
			JTextField field = panel.getField(base);
			for(int i = 0; i < characters.length(); i++) {
				//-- At the caret, which after a setText is the end - the same place typing lands.
				int at = field.getCaretPosition();
				field.replaceSelection(String.valueOf(characters.charAt(i)));
				if(field.getCaretPosition() < at)
					throw new IllegalStateException("the caret went backwards");
			}
		});
	}

	private static void setText(NumberConverterPanel panel, Base base, String value) {
		Edt.run(() -> panel.getField(base).setText(value));
	}

	// ---------------------------------------------------------------------------------------
	// Converting
	// ---------------------------------------------------------------------------------------

	@Test
	void itStartsAtZeroInEveryBase() {
		NumberConverterPanel panel = panel();
		assertEquals("0", text(panel, Base.OCTAL));
		assertEquals("0", text(panel, Base.DECIMAL));
		assertEquals("0", text(panel, Base.HEX));
		assertEquals(16, Edt.call(panel::bits), "a PDP-11 word, not the original's 32 bits");
		assertEquals("0 000 000 000 000 000", Edt.call(() -> panel.getBinaryText(Base.OCTAL)));
	}

	@Test
	void typingOctalFillsInTheOthers() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "177777");
		assertEquals("65535", text(panel, Base.DECIMAL));
		assertEquals("FFFF", text(panel, Base.HEX));
		assertEquals(0177777, Edt.call(panel::getValue));
	}

	@Test
	void typingHexFillsInTheOthers() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.HEX, "beef");
		assertEquals("137357", text(panel, Base.OCTAL));
		assertEquals("48879", text(panel, Base.DECIMAL));
	}

	@Test
	void typingDecimalFillsInTheOthers() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.DECIMAL, "511");
		assertEquals("777", text(panel, Base.OCTAL));
		assertEquals("1FF", text(panel, Base.HEX));
	}

	@Test
	void theFieldBeingTypedInIsLeftAloneSoTheCaretStaysPut() {
		//-- Rewriting the field under the caret moves the caret to the front, and typing "1"
		//-- then "2" would leave "21". This is why updateEdits skips its source edit.
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "");
		type(panel, Base.OCTAL, "123");
		assertEquals("123", text(panel, Base.OCTAL));
		assertEquals("83", text(panel, Base.DECIMAL));
	}

	@Test
	void theBinaryLinesAreGroupedForTheirOwnBase() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "177777");
		//-- Threes under octal, so the columns line up with the six digits above them; fours
		//-- under hex, one per hex digit.
		assertEquals("1 111 111 111 111 111", Edt.call(() -> panel.getBinaryText(Base.OCTAL)));
		assertEquals("1111 1111 1111 1111", Edt.call(() -> panel.getBinaryText(Base.HEX)));
		assertEquals("1111111111111111", Edt.call(() -> panel.getBinaryText(Base.DECIMAL)),
			"decimal has no grouping that would line up");
	}

	@Test
	void aNegativeWordIsReadableAsOne() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "177777");
		assertEquals("-1", Edt.call(panel::getSignedText));
		setText(panel, Base.OCTAL, "100000");
		assertEquals("-32768", Edt.call(panel::getSignedText));
		setText(panel, Base.OCTAL, "7");
		assertEquals("7", Edt.call(panel::getSignedText));
	}

	// ---------------------------------------------------------------------------------------
	// What is refused
	// ---------------------------------------------------------------------------------------

	@Test
	void aDigitFromAnotherBaseNeverArrives() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "");
		type(panel, Base.OCTAL, "1837");
		assertEquals("137", text(panel, Base.OCTAL), "the 8 was never let in");
	}

	@Test
	void pastingSomethingWithPunctuationInItKeepsTheDigits() {
		//-- The case the original's own commented-out stripInvalidDigits was written for, and
		//-- which raises an exception in it as shipped.
		NumberConverterPanel panel = panel();
		setText(panel, Base.DECIMAL, "");
		Edt.run(() -> panel.getField(Base.DECIMAL).replaceSelection("1,234"));
		assertEquals("1234", text(panel, Base.DECIMAL));
		assertEquals("2322", text(panel, Base.OCTAL));
	}

	@Test
	void oneDigitTooManyIsRefusedRatherThanChangingTheNumber() {
		//-- The original drops the leading digit to make room, so 177777 followed by a 7 becomes
		//-- 777777 - a different number, silently.
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "177777");
		type(panel, Base.OCTAL, "7");
		assertEquals("177777", text(panel, Base.OCTAL), "nothing happened, which is the point");
		assertEquals(0177777, Edt.call(panel::getValue));
	}

	@Test
	void aWiderWidthLetsTheSameFieldHoldMore() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "177777");
		type(panel, Base.OCTAL, "7");
		assertEquals("177777", text(panel, Base.OCTAL));

		Edt.run(() -> panel.getWidthSelector().setSelectedItem(22));
		type(panel, Base.OCTAL, "7");
		assertEquals("1777777", text(panel, Base.OCTAL), "22 bits has room for it");
	}

	@Test
	void aNarrowerWidthTruncatesAndSaysSo() {
		NumberConverterPanel panel = panel();
		Edt.run(() -> panel.getWidthSelector().setSelectedItem(22));
		setText(panel, Base.OCTAL, "17777777");
		Edt.run(() -> panel.getWidthSelector().setSelectedItem(16));

		assertEquals("177777", text(panel, Base.OCTAL), "the bits that fell off are gone");
		assertTrue(Edt.call(panel::getNoteText).contains("Truncated to 16 bits"),
			Edt.call(panel::getNoteText));
	}

	// ---------------------------------------------------------------------------------------
	// Keys
	// ---------------------------------------------------------------------------------------

	@Test
	void escapeClearsEverything() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "12345");
		press(panel, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
		assertEquals("0", text(panel, Base.OCTAL));
		assertEquals("0", text(panel, Base.DECIMAL));
		assertEquals("0", text(panel, Base.HEX));
		assertEquals(0, Edt.call(panel::getValue));
	}

	@Test
	void altKeysMoveBetweenTheBasesAsTheyDoInTheOriginal() {
		NumberConverterPanel panel = panel();
		//-- Focus needs a window, which a headless test has not got; what can be checked without
		//-- one is that the keys are bound at all, and to the right thing.
		for(int key : new int[] {KeyEvent.VK_O, KeyEvent.VK_H, KeyEvent.VK_D}) {
			KeyStroke stroke = KeyStroke.getKeyStroke(key, KeyEvent.ALT_DOWN_MASK);
			assertTrue(Edt.call(() -> panel.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
				.get(stroke) != null), "Alt-" + (char) key + " should be bound");
		}
	}

	private static void press(NumberConverterPanel panel, KeyStroke stroke) {
		Edt.run(() -> {
			Object name = panel.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
			panel.getActionMap().get(name).actionPerformed(new ActionEvent(panel, 0, ""));
		});
	}

	// ---------------------------------------------------------------------------------------
	// Layout
	// ---------------------------------------------------------------------------------------

	@Test
	void theFieldsGetTheWidthAndTheLabelsDoNot() {
		NumberConverterPanel panel = panel();
		setText(panel, Base.OCTAL, "177777");
		Edt.run(() -> UiRenderer.layOut(panel, WIDTH, HEIGHT));
		Rectangle octal = Edt.call(() -> panel.getField(Base.OCTAL).getBounds());
		assertTrue(octal.width > WIDTH / 2, "the field gets the room: " + octal);
		assertTrue(octal.x + octal.width <= WIDTH, "and stays inside the panel");
	}

	@Test
	void renderToAFileForLookingAt() throws Exception {
		NumberConverterPanel panel = panel();
		Edt.run(() -> panel.getWidthSelector().setSelectedItem(16));
		setText(panel, Base.OCTAL, "112737");
		Path file = Edt.call(() -> UiRenderer.renderToFile(panel, WIDTH, HEIGHT,
			Path.of("target", "ui-render", "number-converter-panel.png")));
		assertTrue(java.nio.file.Files.size(file) > 0);
	}
}
