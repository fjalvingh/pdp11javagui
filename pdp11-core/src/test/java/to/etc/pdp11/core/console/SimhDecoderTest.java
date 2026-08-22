package to.etc.pdp11.core.console;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.mem.MemoryCellGroups;
import to.etc.pdp11.core.util.Logger;
import to.etc.pdp11.core.util.Scheduler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SimH decoder on its own: bytes in, phrases out, no connection and no machine.
 *
 * <p>Everything here is a transcript observed live against a real SimH and recorded in PLAN.md
 * phase 3. The awkward ones are deliberate - a prompt glued to the end of an unterminated line,
 * an echo that looks exactly like a rejection, a reply arriving one byte at a time.</p>
 */
class SimhDecoderTest {
	private SimhConsole console() {
		return new SimhConsole(new MemoryCellGroups(), Logger.NULL, new Scheduler.Manual());
	}

	private List<AnswerPhrase> decode(String received) {
		SimhConsole c = console();
		c.onSerialReceive(received);
		return c.getAnswers().snapshot();
	}

	/** One byte at a time, which is what a serial line actually does. */
	private List<AnswerPhrase> decodeByteWise(String received) {
		SimhConsole c = console();
		for(int i = 0; i < received.length(); i++) {
			c.onSerialReceive(received.substring(i, i + 1));
		}
		return c.getAnswers().snapshot();
	}

	@Test
	void aBarePromptIsAPrompt() {
		List<AnswerPhrase> l = decode("sim> ");
		assertEquals(1, l.size());
		assertInstanceOf(AnswerPhrase.Prompt.class, l.get(0));
	}

	@Test
	void anIncompleteLineYieldsNothingUntilItEnds() {
		SimhConsole c = console();
		c.onSerialReceive("1000:\t1234");
		assertEquals(0, c.getAnswers().size(), "a line with no terminator is not an answer yet");
		c.onSerialReceive("56\r\n");
		assertEquals(1, c.getAnswers().size());
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) c.getAnswers().get(0);
		assertEquals(0123456, r.value().word());
	}

	@Test
	void anExamineExchangeDecodesToPromptEchoAnswerPrompt() {
		//-- The prompt comes first because it was printed at the end of the previous command;
		//-- then SimH echoes what it is about to run. This ordering is the reason the console
		//-- synchronises on the echo rather than on the prompt.
		List<AnswerPhrase> l = decode("sim> E 1000\r\n1000:\t123456\r\nsim> ");
		assertEquals(4, l.size(), l.toString());
		assertInstanceOf(AnswerPhrase.Prompt.class, l.get(0));
		assertEquals("E 1000", ((AnswerPhrase.OtherLine) l.get(1)).text());
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) l.get(2);
		assertEquals(01000, r.examineAddr().val());
		assertEquals(MemoryAddressType.PHYSICAL22, r.examineAddr().type());
		assertEquals(0123456, r.value().word());
		assertInstanceOf(AnswerPhrase.Prompt.class, l.get(3));
	}

	@Test
	void theSameExchangeDecodesIdenticallyOneByteAtATime() {
		String transcript = "sim> E 1000\r\n1000:\t123456\r\nsim> ";
		assertEquals(decode(transcript).toString(), decodeByteWise(transcript).toString());
	}

	@Test
	void aPromptGluedToAnUnterminatedLineIsStillSeen() {
		//-- SimH prints "Simulator Running..." with no CRLF before the next prompt
		//-- (_sim_rem_message() in scp.c). Testing for equality with the prompt would scan
		//-- past it forever, because no line end is ever coming.
		List<AnswerPhrase> l = decode("Simulator Running...sim> ");
		assertEquals(2, l.size(), l.toString());
		assertEquals("Simulator Running...", ((AnswerPhrase.OtherLine) l.get(0)).text());
		assertInstanceOf(AnswerPhrase.Prompt.class, l.get(1));
	}

	@Test
	void simulatorRunningSetsTheCpuStateRunning() {
		SimhConsole c = console();
		assertEquals(SimhConsole.CpuState.UNKNOWN, c.getCpuState());
		//-- The line has to be complete before it is a phrase at all, and the state only moves
		//-- on a confirmed phrase - never on a partial line, and never on a guess.
		c.onSerialReceive("Simulator Running...");
		assertEquals(SimhConsole.CpuState.UNKNOWN, c.getCpuState());
		c.onSerialReceive("\r\n");
		assertEquals(SimhConsole.CpuState.RUNNING, c.getCpuState());
		//-- And a prompt is only ever sent when SimH is not running.
		c.onSerialReceive("sim> ");
		assertEquals(SimhConsole.CpuState.HALTED, c.getCpuState());
	}

	@Test
	void everyShapeOfStopReportIsAHalt() {
		for(String line : List.of(
			"Simulation stopped, PC: 002502 (MOV (SP)+,177776)",
			"HALT instruction, PC: 000114 (SWAB (R0)+)",
			"Step expired, PC: 000006 (SWAB -(R0))")) {
			List<AnswerPhrase> l = decode(line + "\r\n");
			AnswerPhrase.Halt h = assertInstanceOf(AnswerPhrase.Halt.class, l.get(0), line);
			assertEquals(MemoryAddressType.VIRTUAL, h.haltAddr().type(),
				"a console reports the PC as the program sees it");
		}
		assertEquals(01234, ((AnswerPhrase.Halt) decode("HALT instruction, PC: 001234 (HALT)\r\n").get(0))
			.haltAddr().val());
	}

	@Test
	void addressSpaceExceededIsAnAnswerWithNoAddress() {
		//-- A UNIBUS timeout is a valid answer, but SimH does not say which address it was
		//-- about - so the caller has to attribute it to whatever it asked for next.
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) decode("Address space exceeded\r\n").get(0);
		assertNull(r.examineAddr());
		assertTrue(r.isTimeout());
	}

	@Test
	void registerRepliesComeBackByName() {
		AnswerPhrase.ExamineResult r = (AnswerPhrase.ExamineResult) decode("PC:\t001000\r\n").get(0);
		assertEquals(017777707L, r.examineAddr().val());
		assertEquals(01000, r.value().word());
	}

	@Test
	void aLineThatIsNotAnExamineAnswerIsJustALine() {
		//-- Three words, so not "<addr>: <value>"; and a value wider than a word, which
		//-- OctalStr2Dword(s,16) turns into the illegal-value sentinel and so rejects.
		assertInstanceOf(AnswerPhrase.OtherLine.class, decode("1000:\t123456\t777\r\n").get(0));
		assertInstanceOf(AnswerPhrase.OtherLine.class, decode("1000:\t7777777\r\n").get(0));
		assertInstanceOf(AnswerPhrase.OtherLine.class, decode("Unknown command\r\n").get(0));
	}

	@Test
	void fillNulsAreDroppedWhereverTheyAppear() {
		//-- Some consoles pad with NULs and they can turn up anywhere, including mid-number.
		AnswerPhrase.ExamineResult r =
			(AnswerPhrase.ExamineResult) decode("1000:\t12" + (char) 0 + "3456\r\n").get(0);
		assertEquals(0123456, r.value().word());
	}
}
