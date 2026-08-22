package to.etc.pdp11.ui;

import java.awt.Color;

/**
 * The two colours the Pascal UI uses to mean something, carried across.
 *
 * <p>Both come from {@code AuxU.pas:47-58}, where they are written as Delphi {@code TColor}
 * literals - which are <b>BGR</b>, not RGB. {@code $80FFFF} is not a pale blue, it is the light
 * yellow the comment beside it says it is; getting that backwards is the kind of mistake that
 * survives a port for years because the result still looks deliberate.</p>
 */
public final class UiColors {
	private UiColors() {
	}

	/** A cell the user has typed into and not deposited yet. {@code ColorGridCellChangedBkGnd}. */
	public static final Color EDITED_BACKGROUND = new Color(0xFF, 0xFF, 0x80);

	public static final Color EDITED_TEXT = Color.BLACK;

	/** The line the program counter is on. {@code ColorCodeExecutionPositionBkGnd}. */
	public static final Color PC_BACKGROUND = new Color(0xCC, 0x99, 0x99);

	public static final Color PC_TEXT = Color.BLACK;

	/** A value the machine has never been asked for. */
	public static final Color UNKNOWN_TEXT = new Color(0x90, 0x90, 0x90);
}
