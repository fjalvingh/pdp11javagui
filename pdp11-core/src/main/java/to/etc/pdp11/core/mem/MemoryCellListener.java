package to.etc.pdp11.core.mem;

/**
 * Told when a cell changed for a reason other than this listener's own doing - because the
 * machine was examined, or because another window deposited to the same address.
 *
 * <p>Replaces {@code TMemoryCellChangeEvent} ({@code MemoryCellU.pas:99}), which is a
 * <b>single delegate</b> per group. A second subscriber silently unsubscribed the first, and
 * {@code FrameMemoryCellGroupListU.pas:318} relies on assigning {@code nil} to unsubscribe -
 * which would also silently unsubscribe whoever came after. With eight subscription sites in
 * the Pascal that is a bug waiting for the ninth.</p>
 */
@FunctionalInterface
public interface MemoryCellListener {
	/**
	 * @param group the group the cell belongs to - the Pascal passes this as {@code sender}
	 * @param cell  the cell whose {@code pdpValue} just changed
	 */
	void memoryCellChanged(MemoryCellGroup group, MemoryCell cell);
}
