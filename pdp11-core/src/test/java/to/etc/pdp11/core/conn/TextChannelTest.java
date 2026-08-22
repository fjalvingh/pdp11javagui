package to.etc.pdp11.core.conn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The channel both console views read from.
 *
 * <p>The one behaviour worth a test of its own is the one it exists for: subscribing must hand
 * over the backlog and start the live stream <b>without a gap between them</b>, because the whole
 * point is a window opened after something happened that still shows it.</p>
 */
class TextChannelTest {
	private static TextChannel channel() {
		return new TextChannel("test", 1000);
	}

	@Test
	void keepsWhatWasSaid() {
		TextChannel c = channel();
		c.append("sim> ");
		c.append("E 1000\r\n");
		assertEquals("sim> E 1000\r\n", c.getText());
	}

	@Test
	void ignoresNothingAtAll() {
		TextChannel c = channel();
		c.append(null);
		c.append("");
		assertEquals(0, c.length());
	}

	@Test
	void aSubscriberHearsWhatComesNext() {
		TextChannel c = channel();
		List<String> heard = new ArrayList<>();
		c.subscribe(heard::add);
		c.append("one");
		c.append("two");
		assertEquals(List.of("one", "two"), heard);
	}

	@Test
	void aSubscriberIsFirstToldTheBacklog() {
		TextChannel c = channel();
		c.append("before");
		List<String> heard = new ArrayList<>();
		c.subscribe(heard::add);
		c.append("after");
		assertEquals(List.of("before", "after"), heard, "backlog first, then live, in order");
	}

	@Test
	void anEmptyChannelReplaysNothing() {
		TextChannel c = channel();
		List<String> heard = new ArrayList<>();
		c.subscribe(heard::add);
		assertEquals(List.of(), heard, "an empty replay would clear a view for no reason");
	}

	@Test
	void unsubscribingStopsIt() {
		TextChannel c = channel();
		List<String> heard = new ArrayList<>();
		TextChannel.Listener l = heard::add;
		c.subscribe(l);
		c.append("one");
		c.unsubscribe(l);
		c.append("two");
		assertEquals(List.of("one"), heard);
		assertEquals(0, c.listenerCount());
	}

	@Test
	void clearingEmptiesItAndSaysSo() {
		TextChannel c = channel();
		c.append("something");
		AtomicBoolean cleared = new AtomicBoolean();
		c.subscribe(new TextChannel.Listener() {
			@Override
			public void onText(String text) {
			}

			@Override
			public void onCleared() {
				cleared.set(true);
			}
		});
		c.clear();
		assertEquals("", c.getText());
		assertTrue(cleared.get());
	}

	@Test
	void theOldestGoesWhenItIsFull() {
		TextChannel c = new TextChannel("small", 10);
		c.append("0123456789");
		c.append("ABC");
		assertEquals(10, c.length());
		assertEquals("3456789ABC", c.getText());
	}

	@Test
	void aListenerMayUnsubscribeItself() {
		//-- Delivery is under the channel's lock, so a listener that removes itself while being
		//-- called must not take the iteration down with it.
		TextChannel c = channel();
		List<String> heard = new ArrayList<>();
		TextChannel.Listener l = new TextChannel.Listener() {
			@Override
			public void onText(String text) {
				heard.add(text);
				c.unsubscribe(this);
			}
		};
		c.subscribe(l);
		c.append("one");
		c.append("two");
		assertEquals(List.of("one"), heard);
	}

	/**
	 * A writer running while a reader subscribes must lose nothing and see nothing twice.
	 *
	 * <p>This is the race the class exists to close: snapshot-then-subscribe as two calls drops
	 * whatever arrives between them, and it drops it silently.</p>
	 */
	@Test
	void nothingIsLostOrDoubledWhileSubscribing() throws Exception {
		for(int round = 0; round < 20; round++) {
			//-- Big enough that nothing is trimmed: the assertion below is that the backlog plus
			//-- the live stream is the whole thing, and a trim would legitimately break that.
			TextChannel c = new TextChannel("race", 1_000_000);
			int count = 200;
			CountDownLatch started = new CountDownLatch(1);
			Thread writer = new Thread(() -> {
				started.countDown();
				for(int i = 0; i < count; i++) {
					c.append("<" + i + ">");
				}
			});
			writer.start();
			started.await(5, TimeUnit.SECONDS);
			StringBuilder seen = new StringBuilder();
			c.subscribe(seen::append);
			writer.join(5000);
			//-- Whatever the subscriber missed by being late is in the backlog it was handed, so
			//-- the two together are exactly the whole stream, once.
			StringBuilder whole = new StringBuilder();
			for(int i = 0; i < count; i++) {
				whole.append("<").append(i).append(">");
			}
			assertEquals(whole.toString(), seen.toString(), "round " + round);
			assertFalse(c.getText().isEmpty());
		}
	}
}
