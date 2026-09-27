package to.etc.pdp11.ui;

import to.etc.pdp11.core.mem.MemoryCell;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which memory cell the user is currently looking at.
 *
 * <p>The second of the two things a window would otherwise have to tell another window about,
 * after {@link MachineState}. In the Pascal every grid that shows cells calls
 * {@code FormMain.SyncBitfieldForm(mc)} when the selection moves
 * ({@code FrameMemoryCellGroupGridU.pas:159-166}, {@code FrameMemoryCellGroupListU.pas:108-114}),
 * so each of those frames knows the main form exists and the main form knows the Bitfields
 * window exists.</p>
 *
 * <p>Here a view says which cell is selected and whoever cares subscribes. The Bitfields window
 * is the only subscriber today; nothing about adding a second one touches the views.</p>
 *
 * <p>Everything happens on the event thread - selections are made there and displayed there -
 * but {@link #select} marshals anyway, because a console job that wants to point the Bitfields
 * window at something has no business knowing which thread it is on.</p>
 */
public final class CellSelection {
	@FunctionalInterface
	public interface Listener {
		void cellSelected(MemoryCell cell);
	}

	private final List<Listener> m_listeners = new CopyOnWriteArrayList<>();

	private volatile MemoryCell m_selected;

	/** The cell being looked at, or null if nothing is. */
	public MemoryCell getSelected() {
		return m_selected;
	}

	public void addListener(Listener l) {
		m_listeners.add(l);
	}

	public void removeListener(Listener l) {
		m_listeners.remove(l);
	}

	/** Say what is selected now. A null clears it, which is what an emptied view means. */
	public void select(MemoryCell cell) {
		m_selected = cell;
		if(SwingUtilities.isEventDispatchThread())
			fire(cell);
		else
			SwingUtilities.invokeLater(() -> fire(cell));
	}

	private void fire(MemoryCell cell) {
		for(Listener l : m_listeners) {
			l.cellSelected(cell);
		}
	}
}
