package to.etc.pdp11.core.io;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.fake.FakePdp11Odt;
import to.etc.pdp11.core.util.Scheduler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport boundary: the telnet IAC filter, the SimH port probe, and a fake driven
 * through {@link FakeTransport} the way the console layer will drive it.
 */
class TransportTest {
	private static final int IAC = 0xFF;

	private static final int WILL = 0xFB;

	private static final int WONT = 0xFC;

	private static final int DO = 0xFD;

	private static final int DONT = 0xFE;

	private static final int SB = 0xFA;

	private static final int SE = 0xF0;

	private static final int OPT_ECHO = 1;

	// ---------------------------------------------------------------------------------------
	// FakeTransport
	// ---------------------------------------------------------------------------------------

	private static FakeTransport odtTransport() {
		FakePdp11Odt odt = new FakePdp11Odt(MemoryAddressType.PHYSICAL22,
			new Scheduler.Manual(), new Random(1));
		odt.powerOn();
		return new FakeTransport(odt);
	}

	@Test
	void afakeAnswersThroughTheTransportJustLikeAWire() throws IOException {
		FakeTransport t = odtTransport();
		byte[] buf = new byte[256];

		//-- The power-on banner is waiting before anything is typed.
		int n = t.read(buf, 0, buf.length);
		assertEquals("\r\n173000\r\n@", new String(buf, 0, n, StandardCharsets.ISO_8859_1));

		t.write("1000/".getBytes(StandardCharsets.ISO_8859_1));
		n = t.read(buf, 0, buf.length);
		assertEquals("1000/000000 ", new String(buf, 0, n, StandardCharsets.ISO_8859_1));
		t.close();
	}

	/**
	 * A read with nothing waiting blocks until a write produces something. This is what lets
	 * one reader thread per connection replace the Pascal's 10 ms poll timer.
	 */
	@Test
	void aReadBlocksUntilThereIsSomethingToRead() throws Exception {
		FakeTransport t = odtTransport();
		byte[] drain = new byte[256];
		t.read(drain, 0, drain.length);                     // the banner

		CountDownLatch reading = new CountDownLatch(1);
		AtomicReference<String> got = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			try {
				byte[] buf = new byte[64];
				reading.countDown();
				int n = t.read(buf, 0, buf.length);
				got.set(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
			} catch(IOException x) {
				got.set("failed: " + x);
			}
		});
		reader.start();
		assertTrue(reading.await(2, TimeUnit.SECONDS));
		//-- Nothing has been typed, so the reader is parked.
		reader.join(100);
		assertTrue(reader.isAlive());

		t.write('R');
		reader.join(2000);
		assertEquals("R", got.get());
		t.close();
	}

	/** Closing wakes a blocked reader, which is how a reader thread is stopped. */
	@Test
	void closingWakesABlockedReaderWithEndOfStream() throws Exception {
		FakeTransport t = odtTransport();
		byte[] drain = new byte[256];
		t.read(drain, 0, drain.length);

		AtomicInteger result = new AtomicInteger(Integer.MIN_VALUE);
		Thread reader = new Thread(() -> {
			try {
				result.set(t.read(new byte[16], 0, 16));
			} catch(IOException x) {
				result.set(-99);
			}
		});
		reader.start();
		Thread.sleep(50);
		t.close();
		reader.join(2000);
		assertEquals(-1, result.get());
		assertFalse(t.isOpen());
	}

	/**
	 * Output can appear without a keystroke: a simulated program halts on the scheduler's
	 * thread. A reader waiting for bytes has to be woken by that too.
	 */
	@Test
	void aHaltFiringOnAnotherThreadWakesTheReader() throws Exception {
		Scheduler.Manual scheduler = new Scheduler.Manual();
		FakePdp11Odt odt = new FakePdp11Odt(MemoryAddressType.PHYSICAL22, scheduler, new Random(7));
		odt.powerOn();
		odt.setRunMode(true);
		FakeTransport t = new FakeTransport(odt);
		byte[] drain = new byte[256];
		t.read(drain, 0, drain.length);                     // banner

		t.write("1000G".getBytes(StandardCharsets.ISO_8859_1));
		t.read(drain, 0, drain.length);                     // the echoed "1000G"

		AtomicReference<String> got = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			try {
				byte[] buf = new byte[64];
				int n = t.read(buf, 0, buf.length);
				got.set(n < 0 ? "<eof>" : new String(buf, 0, n, StandardCharsets.ISO_8859_1));
			} catch(IOException x) {
				got.set("failed: " + x);
			}
		});
		reader.start();
		Thread.sleep(50);
		assertTrue(reader.isAlive(), "nothing has halted yet, so the reader should be parked");

		//-- Fire the run-to-halt from this thread, standing in for the scheduler's.
		scheduler.fireAll();
		reader.join(2000);
		assertNotEquals(null, got.get());
		assertTrue(got.get().startsWith("\r\n"), "the halt report should have arrived: " + got.get());
		t.close();
	}

	// ---------------------------------------------------------------------------------------
	// Telnet
	// ---------------------------------------------------------------------------------------

	/**
	 * Serve one connection, send {@code toSend}, and record what the client sends back.
	 * Returns the recorded bytes once the client closes.
	 */
	private static byte[] runTelnetServer(ServerSocket server, byte[] toSend,
		java.util.function.Consumer<TelnetTransport> body) throws Exception {
		AtomicReference<byte[]> received = new AtomicReference<>(new byte[0]);
		Thread serverThread = new Thread(() -> {
			try(Socket s = server.accept()) {
				OutputStream os = s.getOutputStream();
				os.write(toSend);
				os.flush();
				//-- Read to end of stream rather than once: the client flushes each option
				//-- refusal separately, so TCP is free to deliver them as separate packets.
				java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[256];
				int n;
				while((n = s.getInputStream().read(buf)) > 0) {
					acc.write(buf, 0, n);
				}
				received.set(acc.toByteArray());
			} catch(IOException x) {
				//-- The client closing first is the normal end of these tests.
			}
		});
		serverThread.start();
		try(TelnetTransport t = new TelnetTransport("localhost", server.getLocalPort(), 2000)) {
			body.accept(t);
		}
		serverThread.join(2000);
		return received.get();
	}

	@Test
	void iacNegotiationIsStrippedFromWhatTheReaderSees() throws Exception {
		byte[] fromServer = {
			(byte) IAC, (byte) WILL, (byte) OPT_ECHO,
			'@',
			(byte) IAC, (byte) DO, (byte) OPT_ECHO,
			'1', '0', '0', '0'
		};
		try(ServerSocket server = new ServerSocket(0)) {
			AtomicReference<String> got = new AtomicReference<>("");
			runTelnetServer(server, fromServer, t -> {
				try {
					byte[] buf = new byte[64];
					StringBuilder sb = new StringBuilder();
					while(sb.length() < 5) {
						int n = t.read(buf, 0, buf.length);
						if(n < 0)
							break;
						sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
					}
					got.set(sb.toString());
				} catch(IOException x) {
					got.set("failed: " + x);
				}
			});
			assertEquals("@1000", got.get());
		}
	}

	@Test
	void everyOptionOfferIsRefused() throws Exception {
		byte[] fromServer = {
			(byte) IAC, (byte) WILL, (byte) OPT_ECHO,
			(byte) IAC, (byte) DO, 3,
			'x'
		};
		try(ServerSocket server = new ServerSocket(0)) {
			byte[] replies = runTelnetServer(server, fromServer, t -> {
				try {
					t.read(new byte[16], 0, 16);            // drives the filter
				} catch(IOException x) {
					throw new IllegalStateException(x);
				}
			});
			//-- WILL is answered DONT, DO is answered WONT. Nothing is ever agreed to.
			assertArrayEquals(new byte[]{
				(byte) IAC, (byte) DONT, (byte) OPT_ECHO,
				(byte) IAC, (byte) WONT, 3
			}, replies);
		}
	}

	@Test
	void subnegotiationIsDiscardedWholesale() throws Exception {
		byte[] fromServer = {
			'a',
			(byte) IAC, (byte) SB, 24, 1, 'A', 'B', (byte) IAC, (byte) SE,
			'b'
		};
		try(ServerSocket server = new ServerSocket(0)) {
			AtomicReference<String> got = new AtomicReference<>("");
			runTelnetServer(server, fromServer, t -> {
				try {
					byte[] buf = new byte[64];
					StringBuilder sb = new StringBuilder();
					while(sb.length() < 2) {
						int n = t.read(buf, 0, buf.length);
						if(n < 0)
							break;
						sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
					}
					got.set(sb.toString());
				} catch(IOException x) {
					got.set("failed: " + x);
				}
			});
			assertEquals("ab", got.get());
		}
	}

	/** A doubled 0xFF is one literal data byte, not a command. */
	@Test
	void aDoubledIacIsOneDataByte() throws Exception {
		byte[] fromServer = {'a', (byte) IAC, (byte) IAC, 'b'};
		try(ServerSocket server = new ServerSocket(0)) {
			AtomicReference<byte[]> got = new AtomicReference<>(new byte[0]);
			runTelnetServer(server, fromServer, t -> {
				try {
					byte[] buf = new byte[64];
					int n = t.read(buf, 0, buf.length);
					got.set(java.util.Arrays.copyOf(buf, Math.max(n, 0)));
				} catch(IOException x) {
					throw new IllegalStateException(x);
				}
			});
			assertArrayEquals(new byte[]{'a', (byte) IAC, 'b'}, got.get());
		}
	}

	/** ...and writing a literal 0xFF has to double it on the way out. */
	@Test
	void aLiteralFfIsEscapedOnTheWayOut() throws Exception {
		try(ServerSocket server = new ServerSocket(0)) {
			byte[] replies = runTelnetServer(server, new byte[0], t -> {
				try {
					t.write(new byte[]{'a', (byte) 0xFF, 'b'});
				} catch(IOException x) {
					throw new IllegalStateException(x);
				}
			});
			assertArrayEquals(new byte[]{'a', (byte) IAC, (byte) IAC, 'b'}, replies);
		}
	}

	// ---------------------------------------------------------------------------------------
	// SimH port probing
	// ---------------------------------------------------------------------------------------

	@Test
	void thePortProbeStartsAtTheFamiliarPairAndStepsInTwos() throws IOException {
		SimhProcessTransport.Ports p = SimhProcessTransport.findFreePorts(to.etc.pdp11.core.util.Logger.NULL);
		assertEquals(p.remote() + 1, p.console(), "the pair must stay adjacent");
		assertEquals(0, (p.remote() - SimhProcessTransport.REMOTE_PORT_BASE) % 2);
		assertTrue(p.remote() >= SimhProcessTransport.REMOTE_PORT_BASE);
	}

	/**
	 * A port already in use is skipped. Note the probe deliberately does not set
	 * {@code SO_REUSEADDR}, because SimH does not either - a port SimH could not bind must
	 * count as busy. See {@code SimhProcessTransport.findFreePorts}.
	 */
	@Test
	void aBusyPortPairIsSkipped() throws IOException {
		try(ServerSocket hog = new ServerSocket()) {
			hog.setReuseAddress(false);
			try {
				hog.bind(new java.net.InetSocketAddress(SimhProcessTransport.REMOTE_PORT_BASE));
			} catch(IOException x) {
				return;                                     // something else already has it
			}
			SimhProcessTransport.Ports p =
				SimhProcessTransport.findFreePorts(to.etc.pdp11.core.util.Logger.NULL);
			assertNotEquals(SimhProcessTransport.REMOTE_PORT_BASE, p.remote());
			assertEquals(p.remote() + 1, p.console());
		}
	}

	/**
	 * The generated configuration drops whatever remote/console directives the user's file
	 * already had, so ours always win.
	 */
	@Test
	void theGeneratedSimhIniOverridesTheUsersRemoteAndConsoleLines(@org.junit.jupiter.api.io.TempDir
		java.nio.file.Path dir) throws IOException {
		java.nio.file.Path user = dir.resolve("user.ini");
		java.nio.file.Files.writeString(user, String.join("\n",
			"set cpu 11/70",
			"set remote telnet=9999",
			"  SET CONSOLE TELNET=8888",
			"attach rl0 disk.dsk"));

		SimhProcessTransport.Ports ports = new SimhProcessTransport.Ports(4010, 4011);
		java.nio.file.Path out = SimhProcessTransport.generateIniFile(user, dir, ports);
		java.util.List<String> lines = java.nio.file.Files.readAllLines(out);

		assertTrue(lines.contains("set cpu 11/70"));
		assertTrue(lines.contains("attach rl0 disk.dsk"));
		assertFalse(lines.contains("set remote telnet=9999"));
		assertFalse(lines.stream().anyMatch(l -> l.contains("8888")));
		assertTrue(lines.contains("set remote telnet=4010"));
		assertTrue(lines.contains("set console telnet=4011"));
		assertTrue(lines.contains("set remote master"));
		assertTrue(lines.contains("set remote connections=1"));
	}
}
