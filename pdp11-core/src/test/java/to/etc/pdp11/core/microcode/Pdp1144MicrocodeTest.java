package to.etc.pdp11.core.microcode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The microcode reader, against DEC's own listing.
 *
 * <p>The whole of {@code EY-C3012-RB-001} is packaged with the parser, so most of this is run
 * against 1018 real microwords rather than against examples written to suit the parser. That
 * matters more here than in most places: a hand-written fixture would agree with whatever the
 * field map happens to do, and the field map is the part that is easy to get wrong and
 * impossible to notice being wrong - a microword decoded through a bit map that is off by one
 * still comes out looking like a microword.</p>
 *
 * <p>What makes the real listing usable as a fixture is that it cross-checks itself: the address
 * is printed twice per line, and each microword's next-address <i>bits</i> have to agree with
 * the {@code J/<tag>} in its source <i>text</i>. Nothing decoded with a wrong bit map survives
 * that.</p>
 */
class Pdp1144MicrocodeTest {
	private static final Pdp1144Microcode CODE = Pdp1144Microcode.builtin();

	// -----------------------------------------------------------------------------------------
	// The real listing
	// -----------------------------------------------------------------------------------------

	@Test
	void thePackagedListingLoadsAndVerifies() {
		assertEquals(1018, CODE.size(), "microwords in EY-C3012-RB-001");
		assertEquals(List.of(), CODE.getProblems(), "a sound listing has nothing to complain about");
		assertEquals(0, CODE.byAddress().get(0).getAddress());
		assertEquals(01777, CODE.byAddress().get(CODE.size() - 1).getAddress());
	}

	/**
	 * The cross-check itself, spelled out rather than left inside {@code verify()}: every
	 * microword's decoded next address is a microword, and the tag at that address is the one
	 * its own source text says it jumps to.
	 */
	@Test
	void everyMicrowordsNextAddressAgreesWithItsOwnSourceText() {
		for(MicroInstruction mi : CODE.byAddress()) {
			MicroInstruction next = CODE.atAddress(mi.getNextAddress());
			assertNotNull(next, mi + " goes to " + mi.getNextAddressOctal());
			assertTrue(mi.getOperations().contains("J/" + next.getSymbolicTag()),
				mi + " should say J/" + next.getSymbolicTag());
		}
	}

	/**
	 * The two microwords the Pascal's own header comment uses as its worked examples
	 * ({@code Pdp1144MicroCodeU.pas:30-36}), decoded field by field.
	 *
	 * <p>{@code R0_R0+1} has to come out as the ALU adding one to something, the scratch pad
	 * writing it back, and the register being R0. If the bit map were wrong the values would
	 * still be values.</p>
	 */
	@Test
	void theIncrementMicrowordDecodesToAnIncrement() {
		MicroInstruction mi = CODE.atAddress(0461);
		assertNotNull(mi);
		assertEquals("2-I", mi.getSymbolicTag());
		assertEquals(1062, mi.getLineNumber());
		assertEquals(List.of("R0:=R0+1", "J/1-A"), mi.getOperations(), "the _ is an assignment");
		assertEquals("A PLUS 1", text(mi, "ALU/BLEG CONTROL"));
		assertEquals("ROM", text(mi, "SCRATCH PAD DST SELECT"));
		assertEquals("R0", text(mi, "ROM SCRATCH PAD ADDRESS"));
		assertEquals(0, mi.getNextAddress());
		assertEquals("1-A", CODE.atAddress(mi.getNextAddress()).getSymbolicTag());
	}

	/** {@code DATO,UDATA,J/2-L}: a write onto the Unibus, with the AMUX taking the bus data. */
	@Test
	void theUnibusWriteMicrowordDecodesToAUnibusWrite() {
		MicroInstruction mi = CODE.atAddress(0732);
		assertNotNull(mi);
		assertEquals("2-J", mi.getSymbolicTag());
		assertEquals(List.of("DATO", "UDATA", "J/2-L"), mi.getOperations());
		assertEquals("DATO", text(mi, "UNIBUS CONTROL"));
		assertEquals("UBUS", text(mi, "AMUX CONTROL"));
		assertEquals("TRAN", text(mi, "DATA TRAN"));
		assertEquals("LONG CYCLE", text(mi, "CYCLE"), "a bus cycle needs the long one");
		assertEquals(043, mi.getNextAddress());
		assertEquals("2-L", CODE.atAddress(043).getSymbolicTag());
	}

	/**
	 * The point of the default column: a microword is about the two or three fields that are
	 * not at rest, and everything else is noise.
	 */
	@Test
	void mostFieldsAreAtTheirDefaultInAnyOneMicroword() {
		MicroInstruction mi = CODE.atAddress(0461);
		long changed = MicrocodeField.ALL.stream().filter(f -> f.hasDefault() && !mi.isDefault(f)).count();
		assertEquals(3, changed, "the ALU function, the scratch pad destination and the register");
	}

	@Test
	void theNineListingWordsAreKeptAsPrinted() {
		MicroInstruction mi = CODE.atAddress(0461);
		//-- "U 0461, 0000,2042,0001,4140,0140,3033,4000,0422,017", least significant word first.
		assertEquals(017, mi.getRawWords()[0]);
		assertEquals(0422, mi.getRawWords()[1]);
		assertEquals(0000, mi.getRawWords()[8], "the top word holds the next address");
	}

	// -----------------------------------------------------------------------------------------
	// Finding things in it
	// -----------------------------------------------------------------------------------------

	@Test
	void microwordsAreFoundByAddressTagAndListingLine() {
		MicroInstruction mi = CODE.withTag("2-J");
		assertNotNull(mi);
		assertEquals(0732, mi.getAddress());
		assertSame(mi, CODE.atAddress(0732));
		assertSame(mi, CODE.atLineNumber(mi.getLineNumber()));
		assertNull(CODE.withTag("no such tag"));
		assertNull(CODE.atAddress(-1));
	}

	/**
	 * Tag order is flow order, which plain text sorting does not give: {@code 2-I} has to come
	 * before {@code 20-Z}, and the processor's pages before the floating point unit's.
	 */
	@Test
	void tagOrderIsListingFlowOrder() {
		List<MicroInstruction> byTag = CODE.byTag();
		assertTrue(indexOfTag(byTag, "2-I") < indexOfTag(byTag, "20-A"), "2 before 20");
		assertTrue(indexOfTag(byTag, "20-A") < indexOfTag(byTag, "FP1-A"), "the CPU pages come first");
		for(int i = 1; i < byTag.size(); i++)
			assertTrue(byTag.get(i - 1).getSortableTag().compareTo(byTag.get(i).getSortableTag()) <= 0,
				"sorted at " + i);
	}

	/** {@code FP-33AA} is in the listing and fits no pattern; the Pascal names it and so do we. */
	@Test
	void theOneTagThatFitsNoPatternIsStillSortable() {
		MicroInstruction mi = CODE.withTag("FP-33AA");
		assertNotNull(mi, "the listing really does contain this tag");
		assertEquals("FP33-AA", mi.getSortableTag());
	}

	@Test
	void listingOrderIsLineNumberOrder() {
		List<MicroInstruction> byLine = CODE.byLineNumber();
		for(int i = 1; i < byLine.size(); i++)
			assertTrue(byLine.get(i - 1).getLineNumber() < byLine.get(i).getLineNumber(), "sorted at " + i);
	}

	/**
	 * Reading microcode is mostly done backwards - you have the state the machine ended up in
	 * and want to know what could have got it there. The Pascal can only go forwards.
	 */
	@Test
	void aMicrowordKnowsWhatFallsThroughToIt() {
		MicroInstruction start = CODE.withTag("1-A");
		List<MicroInstruction> before = CODE.predecessorsOf(start);
		assertFalse(before.isEmpty(), "something reaches the start of the fetch loop");
		for(MicroInstruction mi : before)
			assertEquals(start.getAddress(), mi.getNextAddress());
		//-- And the relation is the whole of it: every microword is somebody's successor or
		//-- reachable only by a branch, and nothing is claimed to be a predecessor twice.
		long total = CODE.byAddress().stream().mapToLong(mi -> CODE.predecessorsOf(mi).size()).sum();
		assertEquals(CODE.size(), total, "every microword falls through to exactly one place");
	}

	// -----------------------------------------------------------------------------------------
	// Listings that are not sound
	// -----------------------------------------------------------------------------------------

	/**
	 * A microword whose source runs onto the next line is one microword.
	 *
	 * <p>The continuation carries no line number of its own, which is exactly how the reader
	 * tells it from the start of the next microword.</p>
	 */
	@Test
	void aSourceLineThatRunsOnIsJoinedBackOn() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"U 0000, 0010,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    R15_UDATA,BUT SERVICE,J/1-B,SR1 Z",
			"                                                                                ,ONE MORE THING",
			"U 0010, 0000,6345,0300,0146,1740,3033,4000,0000,017     ;1042   010:    1-B:    J/1-A"));
		assertEquals(2, code.size());
		MicroInstruction first = code.atAddress(0);
		assertTrue(first.getOperations().contains("ONE MORE THING"), first.getOperations().toString());
		assertTrue(first.getOperations().contains("J/1-B"), "and the original text is still there");
	}

	/**
	 * The listing has a comment line whose leading {@code ;} was scanned as {@code :} - line
	 * 1020, {@code ":1950"}. It is not a comment by the usual test, and joining it onto the
	 * microword above would corrupt that microword's source text; its line number is what still
	 * says it is a line of the listing rather than a continuation.
	 *
	 * <p>Found by running the cross-checks over the real listing, which is what they are for.</p>
	 */
	@Test
	void aCommentWhoseSemicolonWasMisreadIsNotJoinedOn() {
		String mangled = " ".repeat(56) + ":1950";
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"U 0000, 0010,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-B",
			mangled,
			"U 0010, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1042   010:    1-B:    J/1-A"));
		assertEquals(2, code.size());
		assertEquals(List.of("J/1-B"), code.atAddress(0).getOperations(), "the comment is not part of it");
		assertEquals(List.of(), code.getProblems());
	}

	/** Page headers and free-standing comments are not microwords and are not complaints. */
	@Test
	void headersAndCommentsAreSkipped() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"; 44OUTU.MCR [160,1311] Micro-2.1 1B(41)        14:3:34 14-Sep-1979    Page 21",
			"",
			"                                                        ;1039   ; A COMMENT",
			"U 0000, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-A"));
		assertEquals(1, code.size());
		assertEquals(List.of(), code.getProblems());
	}

	/**
	 * A damaged line costs its own microword and nothing else. The Pascal raises on the first
	 * one and loses the listing.
	 */
	@Test
	void aBrokenLineIsReportedAndTheRestStillLoads() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"U 0000, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-A",
			"U 0010, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1041   777:    1-B:    J/1-A",
			"U 0020, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1042   020:    1-C:    J/1-A"));
		assertEquals(2, code.size(), "the one with the mismatched address is dropped");
		assertEquals(1, code.getProblems().size());
		Pdp1144Microcode.Problem p = code.getProblems().get(0);
		assertEquals(Pdp1144Microcode.ProblemKind.MALFORMED_LINE, p.kind());
		assertEquals(2, p.fileLine());
		assertTrue(p.describe().contains("0777"), p.describe());
	}

	/** The check that catches a misread digit: bits say one thing, the text beside them another. */
	@Test
	void aJumpThatContradictsTheSourceTextIsReported() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"U 0000, 0010,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-A",
			"U 0010, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1041   010:    1-B:    J/1-A"));
		assertEquals(2, code.size(), "both microwords are readable");
		assertEquals(1, code.getProblems().size());
		//-- 1-A's bits point at 010, which is 1-B, but its source says it jumps to itself.
		assertEquals(Pdp1144Microcode.ProblemKind.JUMP_NOT_IN_SOURCE, code.getProblems().get(0).kind());
	}

	@Test
	void aNextAddressWithNoMicrowordIsReported() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"U 0000, 0777,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/9-Z"));
		assertEquals(1, code.size());
		assertEquals(Pdp1144Microcode.ProblemKind.MISSING_NEXT, code.getProblems().get(0).kind());
	}

	@Test
	void twoMicrowordsAtOneAddressAreReported() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"U 0000, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-A",
			"U 0000, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1041   000:    1-B:    J/1-A"));
		assertTrue(code.getProblems().stream()
			.anyMatch(p -> p.kind() == Pdp1144Microcode.ProblemKind.DUPLICATE_ADDRESS), code.getProblems().toString());
	}

	/** A byte no line printer ever produced means the transcription is damaged. */
	@Test
	void aCharacterThatCannotBeInAListingIsReported() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("test", List.of(
			"U 0000, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1040   000:    1-A:    J/1-A",
			"U 0010, 0000,6045,0001,4166,3340,3033,4000,0422,017     ;1041   010:    1-b:    J/1-A"));
		assertEquals(1, code.size(), "the lower case line is skipped, the other one loads");
		assertEquals(Pdp1144Microcode.ProblemKind.ILLEGAL_CHARACTER, code.getProblems().get(0).kind());
	}

	@Test
	void anEmptyListingSaysSoRatherThanFailing() {
		Pdp1144Microcode code = Pdp1144Microcode.parse("nothing.txt", List.of("; a header and no code"));
		assertTrue(code.isEmpty());
		assertTrue(code.isOk());
		assertTrue(code.describe().contains("no microcode"), code.describe());
	}

	// -----------------------------------------------------------------------------------------
	// The field table
	// -----------------------------------------------------------------------------------------

	/**
	 * The 37 fields tile the 104-bit word without overlapping. An overlap would decode two
	 * fields out of the same bits and neither would be wrong-looking.
	 */
	@Test
	void theFieldsDoNotOverlap() {
		boolean[] used = new boolean[104];
		for(MicrocodeField f : MicrocodeField.ALL) {
			for(int b = f.lsb(); b <= f.msb(); b++) {
				assertFalse(used[b], "bit " + b + " is in two fields, the second being " + f);
				used[b] = true;
			}
		}
		//-- Two bits are in no field: 56, which the print set marks unused, and 103, which is
		//-- the eleventh bit of the top listing word - the next-address field below it is ten
		//-- bits wide, so the word has one bit more than the microword needs.
		for(int b = 0; b < 104; b++)
			assertEquals(b != 56 && b != 103, used[b], "bit " + b);
	}

	@Test
	void aFieldNamesItsBitsTheWayAPrintSetDoes() {
		assertEquals("102:93", MicrocodeField.NEXT_ADDRESS.bitRange());
		assertEquals("87", MicrocodeField.byName("LOAD BA").bitRange());
		assertNull(MicrocodeField.byName("LOAD BA").text(2), "a value outside the field has no name");
		assertEquals("", MicrocodeField.byName("LOAD BA").text(0), "and 0 is named, as nothing at all");
	}

	private static String text(MicroInstruction mi, String fieldName) {
		MicrocodeField f = MicrocodeField.byName(fieldName);
		assertNotNull(f, fieldName);
		return mi.getText(f);
	}

	private static int indexOfTag(List<MicroInstruction> list, String tag) {
		for(int i = 0; i < list.size(); i++) {
			if(list.get(i).getSymbolicTag().equals(tag))
				return i;
		}
		throw new IllegalArgumentException("No microword tagged " + tag);
	}
}
