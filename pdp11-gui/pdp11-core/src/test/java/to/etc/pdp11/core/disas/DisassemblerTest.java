package to.etc.pdp11.core.disas;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour {@link DisassemblerSimhCorpusTest} cannot reach: how many words an
 * instruction consumes, what happens when the memory it needs was never read back from the
 * machine, and how a whole image is walked.
 */
class DisassemblerTest {
	private static final int BASE = 01000;

	private static DecodedInstruction at(int... words) {
		return Disassembler.disassemble(MemoryImage.ofWords(BASE, words), BASE);
	}

	@Test
	void countsTheWordsAnInstructionConsumes() {
		//-- MOV R0,R1: no extension words.
		assertEquals(1, at(010001).words());
		//-- MOV #200,R1: one immediate.
		assertEquals(2, at(012701, 0200).words());
		//-- MOV 100(R0),200(R1): two index words.
		assertEquals(3, at(016061, 0100, 0200).words());
		//-- HALT.
		assertEquals(1, at(0).words());
	}

	@Test
	void formatsLikeThePascalIncludingItsTrailingPadding() {
		//-- LowerCase(Format('%-8s%s', ...)) pads the mnemonic to 8 even with no operands.
		assertEquals("halt    ", at(0).text());
		assertEquals("halt", at(0).textTrimmed());
		assertEquals("mov     r0,r1", at(010001).text());
	}

	@Test
	void fallsBackToWordForAnUnknownOpcode() {
		//-- 0000010 is unassigned: it sits between MFPT (0000007) and JMP (0000040). SimH
		//-- refuses it too, printing the bare number.
		DecodedInstruction di = at(0000010);
		assertFalse(di.recognized());
		assertEquals(1, di.words());
		assertEquals(".WORD", di.mnemonic());
		assertEquals("000010", di.operands());
	}

	/**
	 * The case the Disassembler window hits constantly: memory read back from a real machine
	 * is sparse, so an instruction's extension word may simply never have been examined.
	 */
	@Test
	void fallsBackToWordWhenAnExtensionWordWasNeverRead() {
		MemoryImage mem = new MemoryImage();
		mem.putWord(BASE, 012701);              // MOV #x,R1 - needs a word at BASE+2
		DecodedInstruction di = Disassembler.disassemble(mem, BASE);
		assertFalse(di.recognized());
		assertEquals(1, di.words());
		assertEquals("012701", di.operands());

		//-- Supply the word and it decodes.
		mem.putWord(BASE + 2, 0200);
		DecodedInstruction ok = Disassembler.disassemble(mem, BASE);
		assertTrue(ok.recognized());
		assertEquals(2, ok.words());
		assertEquals("mov     #000200,r1", ok.text());
	}

	@Test
	void halfAValidWordIsNotAValidWord() {
		MemoryImage mem = new MemoryImage();
		mem.put(BASE, new byte[]{0x01});        // low byte only
		assertFalse(mem.isWordValid(BASE));
		assertTrue(mem.isByteValid(BASE));
	}

	@Test
	void pcRelativeOperandsResolveAgainstTheAddressAfterTheExtensionWord() {
		//-- CLR 1234(PC) at 1000: the index word is at 1002, PC is 1004 when the machine adds,
		//-- so the target is 1004+1234 = 2240.
		DecodedInstruction di = at(0005067, 01234);
		assertEquals("clr     002240", di.text());
		assertEquals(2, di.words());
	}

	@Test
	void branchOffsetsAreSigned() {
		//-- Branch targets are computed from the word after the branch, at 01002.
		assertEquals("br      001004", at(0000401).text());   // +1 word
		assertEquals("br      001000", at(0000777).text());   // -1 word
		assertEquals("br      000402", at(0000600).text());   // -128 words, the extreme
		//-- SOB always counts backwards, so its unsigned 6-bit field is subtracted.
		assertEquals("sob     r0,001000", at(0077001).text());
		assertEquals("sob     r0,000776", at(0077002).text());
		assertEquals("sob     r5,000604", at(0077577).text());
	}

	@Test
	void addressArithmeticWrapsAtSixtyFourK() {
		MemoryImage mem = new MemoryImage();
		mem.putWord(0177776, 012701);           // last word in the image
		mem.putWord(0, 0200);                   // its extension word wraps to zero
		DecodedInstruction di = Disassembler.disassemble(mem, 0177776);
		assertTrue(di.recognized());
		assertEquals(2, di.words());
		assertEquals("mov     #000200,r1", di.text());
	}

	@Test
	void walkingAnImageSkipsAddressesThatWereNeverRead() {
		MemoryImage mem = new MemoryImage();
		mem.putWord(01000, 010001);             // MOV R0,R1
		mem.putWord(01002, 0000000);            // HALT
		mem.putWord(02000, 000240);             // NOP, with a gap in between

		List<DecodedInstruction> list = Disassembler.disassembleAll(mem);
		assertEquals(3, list.size());
		assertEquals(01000, list.get(0).address());
		assertEquals("mov     r0,r1", list.get(0).text());
		assertEquals(01002, list.get(1).address());
		assertEquals(02000, list.get(2).address());
		assertEquals("nop     ", list.get(2).text());
	}

	@Test
	void listingMatchesThePascalLineFormat() {
		MemoryImage mem = new MemoryImage();
		mem.putWord(01000, 012701);
		mem.putWord(01002, 0000200);
		//-- "AAAAAA: " then three seven-character word slots, blank when unused, then a
		//-- separating space and the instruction text. Spelled out in pieces rather than as
		//-- one string of counted spaces, because that is what the Pascal builds.
		String expected = "001000: " + "012701 " + "000200 " + "       " + " " + "mov     #000200,r1\n";
		assertEquals(expected, Disassembler.listing(mem));
	}

	/**
	 * The FP11 has six accumulators and the operand register field is three bits wide, so AC4
	 * and AC5 are reachable and 6 and 7 name nothing. The Pascal masks the field to two bits
	 * and prints AC0 for AC4; macro11 assembles {@code CLRF AC5} to 0170405, which settles it.
	 */
	@Test
	void floatOperandsNameAllSixAccumulators() {
		assertEquals("clrf    ac0", at(0170400).text());
		assertEquals("clrf    ac5", at(0170405).text());
		assertEquals("clrf    ?6", at(0170406).text());
		assertEquals("clrf    ?7", at(0170407).text());
	}

	/**
	 * SPL's level is bits 2..0. The Pascal reads bits 8..6 and prints {@code SPL 2} for
	 * 0000234; SimH and the processor handbook both say {@code SPL 4}.
	 */
	@Test
	void splReadsItsLevelFromTheLowThreeBits() {
		assertEquals("spl     0", at(0000230).text());
		assertEquals("spl     4", at(0000234).text());
		assertEquals("spl     7", at(0000237).text());
	}

	@Test
	void conditionCodeGroupFollowsThePerFlagEncodingRatherThanSimhsTable() {
		//-- Both words have bit 0 clear, so C is untouched and V is the one that changes.
		assertEquals("cln clz clv", at(0000256).textTrimmed());
		assertEquals("sen sez sev", at(0000276).textTrimmed());
		//-- Their neighbours, which SimH duplicates them from, are unaffected.
		assertEquals("cln clz clc", at(0000255).textTrimmed());
		assertEquals("sen sez sec", at(0000275).textTrimmed());
	}
}
