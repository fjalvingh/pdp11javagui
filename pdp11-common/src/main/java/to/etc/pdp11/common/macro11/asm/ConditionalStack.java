package to.etc.pdp11.common.macro11.asm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The open {@code .IF} blocks, and whether the code at this point is being assembled.
 *
 * <p>A block inside a block that is not being assembled is never assembled either, whatever its
 * own condition; it is still tracked, so that its {@code .ENDC} closes it and not the block
 * around it. {@code .IFF}, {@code .IFT} and {@code .IFTF} switch the innermost block between
 * its condition being false, true, and either.</p>
 */
final class ConditionalStack {
	private static final class Block {
		private final Location m_openedAt;

		private final boolean m_condition;

		private final boolean m_outerActive;

		private boolean m_active;

		private Block(Location openedAt, boolean condition, boolean outerActive) {
			m_openedAt = openedAt;
			m_condition = condition;
			m_outerActive = outerActive;
			m_active = outerActive && condition;
		}
	}

	private final Deque<Block> m_blocks = new ArrayDeque<>();

	/** Whether the code at this point is assembled. */
	boolean isActive() {
		Block b = m_blocks.peek();
		return b == null || b.m_active;
	}

	int depth() {
		return m_blocks.size();
	}

	/** Open a block; inside a block that is not assembled, the condition does not matter. */
	void open(boolean condition, Location at) {
		m_blocks.push(new Block(at, condition, isActive()));
	}

	/**
	 * {@code .IFF}, {@code .IFT} or {@code .IFTF} in the innermost block.
	 *
	 * @return false when there is no block to apply it to
	 */
	boolean subconditional(Directive d) {
		Block b = m_blocks.peek();
		if(b == null)
			return false;
		b.m_active = switch(d) {
			case IFF -> b.m_outerActive && !b.m_condition;
			case IFT -> b.m_outerActive && b.m_condition;
			case IFTF -> b.m_outerActive;
			default -> throw new IllegalArgumentException(d + " is not a subconditional");
		};
		return true;
	}

	/**
	 * {@code .ENDC}.
	 *
	 * @return false when no block is open
	 */
	boolean close() {
		return m_blocks.poll() != null;
	}

	/**
	 * Close every block opened since the stack was {@code depth} deep.
	 *
	 * @return where each closed block was opened, innermost first
	 */
	List<Location> closeTo(int depth) {
		List<Location> closed = new ArrayList<>();
		while(m_blocks.size() > depth)
			closed.add(m_blocks.pop().m_openedAt);
		return closed;
	}
}
