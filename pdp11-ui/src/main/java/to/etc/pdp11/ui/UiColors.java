package to.etc.pdp11.ui;

import java.awt.Color;

/**
 * Every colour in this application that means something, in one place.
 *
 * <p>Two of them are carried across from the Pascal, where they are written as Delphi
 * {@code TColor} literals ({@code AuxU.pas:47-58}) - which are <b>BGR</b>, not RGB.
 * {@code $80FFFF} is not a pale blue, it is the light yellow the comment beside it says it is;
 * getting that backwards is the kind of mistake that survives a port for years because the
 * result still looks deliberate.</p>
 *
 * <h2>Tuned for a dark background</h2>
 *
 * <p>The application runs FlatLaf's Darcula, so the hues are the Pascal's and the luminance is
 * inverted: a marker that was a pale block with dark text on a white grid is a dark block with
 * pale text here. Same meaning, same hue, legible against what is actually behind it - where
 * {@code $80FFFF} straight across would be a screaming yellow rectangle in the middle of a dark
 * table.</p>
 *
 * <p>Nothing outside this class names a colour. That is what makes a second theme a change to
 * one file rather than a hunt through every panel.</p>
 */
public final class UiColors {
	private UiColors() {
	}

	/**
	 * A cell the user has typed into and not deposited yet. {@code ColorGridCellChangedBkGnd},
	 * which is a light yellow, as amber against a dark grid.
	 */
	public static final Color EDITED_BACKGROUND = new Color(0x5C, 0x51, 0x1E);

	public static final Color EDITED_TEXT = new Color(0xF5, 0xE8, 0xA8);

	/**
	 * The line the program counter is on. {@code ColorCodeExecutionPositionBkGnd}, a dusty rose,
	 * as the same hue with the luminance turned round.
	 */
	public static final Color PC_BACKGROUND = new Color(0x6B, 0x45, 0x45);

	public static final Color PC_TEXT = new Color(0xF2, 0xDA, 0xDA);

	/**
	 * The line an assembler error is on. {@code ColorCodeErrorBkGnd}, which is {@code clRed}.
	 *
	 * <p>Darkened rather than taken literally: a full red band behind a line of code in a dark
	 * editor is unreadable, and the marker exists to be read.</p>
	 */
	public static final Color ERROR_BACKGROUND = new Color(0x7A, 0x28, 0x28);

	/** A value the machine has never been asked for. */
	public static final Color UNKNOWN_TEXT = new Color(0x80, 0x80, 0x82);

	/** Detail beside something else: a status bar's second half, an explanatory line. */
	public static final Color SECONDARY_TEXT = new Color(0x9A, 0x9A, 0x9C);

	/** Connected, running, working. */
	public static final Color OK_TEXT = new Color(0x5E, 0xB5, 0x6B);

	/** Failed, refused, or a switch nobody has said the position of. */
	public static final Color ERROR_TEXT = new Color(0xFF, 0x6B, 0x68);
}
