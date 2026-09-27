package to.etc.pdp11.core.microcode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEC's microprogram flow for the KD11-B, against the listing it describes.
 *
 * <p>The two documents were transcribed independently - the listing by a classifier over the bit
 * table, the flow by eye - and both print every microword's address, next address and tag. So
 * the strongest test here is that they agree, which holds both transcriptions down at once: it is
 * how the listing's {@code SBF-1}, {@code DBF-1} and {@code DF-1} were found to be {@code E}s.</p>
 */
class Kd11bFlowTest {
	private static final Kd11bFlow FLOW = Kd11bFlow.builtin();

	private static final Microcode REV_E = Kd11bMicrocode.load(Kd11bMicrocode.Revision.E);

	private static final Microcode REV_F = Kd11bMicrocode.load(Kd11bMicrocode.Revision.F);

	// -----------------------------------------------------------------------------------------
	// The document
	// -----------------------------------------------------------------------------------------

	@Test
	void theFlowHasWhatThePrintedDocumentHas() {
		assertEquals(59, FLOW.getRoutines().size(), "routine headings");
		assertEquals(201, FLOW.getSteps().size(), "microword lines");
		assertEquals(13, FLOW.getNotShown().size(), "page 22's list");
		assertTrue(FLOW.getNotShown().contains("A145"));
		//-- Pages 2 to 22; page 1 is the revision control sheet.
		assertEquals(2, FLOW.getRoutines().get(0).page());
		assertEquals("INSTRUCTION FETCH", FLOW.getRoutines().get(0).heading());
	}

	@Test
	void everyCategoryIsUsed() {
		Set<Kd11bFlow.Category> used = FLOW.getRoutines().stream().map(Kd11bFlow.Routine::category)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(Kd11bFlow.Category.class)));
		assertEquals(EnumSet.allOf(Kd11bFlow.Category.class), used);
	}

	// -----------------------------------------------------------------------------------------
	// Against the listing
	// -----------------------------------------------------------------------------------------

	/**
	 * Every microword the listing prints is either shown in the flow or on the flow's own list of
	 * what it leaves out, and the flow shows nothing the listing does not have.
	 */
	@Test
	void theFlowAccountsForEveryMicrowordAndNoOthers() {
		for(Microcode code : List.of(REV_E, REV_F)) {
			Set<String> listing = code.byAddress().stream().map(MicroInstruction::getSymbolicTag)
				.collect(Collectors.toCollection(TreeSet::new));
			Set<String> flow = new TreeSet<>(FLOW.getNotShown());
			FLOW.getSteps().forEach(s -> flow.add(s.tag()));
			assertEquals(listing, flow, code.getSourceName());
		}
	}

	/** The cross-check: two documents, transcribed two ways, agreeing on 201 addresses and successors. */
	@Test
	void theFlowAndTheListingAgreeOnEveryAddressAndSuccessor() {
		assertEquals(List.of(), FLOW.disagreements(REV_E.byAddress()));
		assertEquals(List.of(), FLOW.disagreements(REV_F.byAddress()));
	}

	@Test
	void everyMicrowordHasARole() {
		for(Microcode code : List.of(REV_E, REV_F)) {
			for(MicroInstruction mi : code.byAddress())
				assertNotNull(code.roleOf(mi), mi.toString());
		}
	}

	// -----------------------------------------------------------------------------------------
	// What a microword is told
	// -----------------------------------------------------------------------------------------

	@Test
	void aMicrowordGetsItsRoutineAndWhatItDoes() {
		MicrowordRole et2 = REV_F.roleOf(REV_F.withTag("ET-2"));
		assertEquals("Trap", et2.category());
		assertEquals("EMT TRAP (VECTOR LOC=30)", et2.routine());
		assertEquals("Trap: EMT TRAP (VECTOR LOC=30)", et2.summary());
		assertEquals("R[12]←B", et2.action());
		assertEquals("K-MP-KD11-B-1 page 16", et2.source());

		assertEquals("Instruction fetch", REV_E.roleOf(REV_E.withTag("F-1")).category());
		assertEquals("Console", REV_E.roleOf(REV_E.withTag("CD1-2")).category());
		assertEquals("Source operand", REV_E.roleOf(REV_E.withTag("S6-1")).category());
	}

	/** "GET TO" is printed above the microword it is about, and it goes there. */
	@Test
	void howAMicrowordIsReachedGoesWithIt() {
		List<String> notes = REV_F.roleOf(REV_F.withTag("ET-2")).notes();
		assertTrue(notes.contains("GET TO ET-2 FROM BT-1 VIA GOTO"), notes.toString());
		assertTrue(REV_F.roleOf(REV_F.withTag("ET-1")).notes().stream().noneMatch(n -> n.contains("FROM BT-1")),
			"not on the microword above it");
	}

	/** Anything else is printed below the microword it is about: a branch list, an explanation. */
	@Test
	void whatFollowsAMicrowordGoesWithIt() {
		List<String> f5 = REV_F.roleOf(REV_F.withTag("F-5")).notes();
		assertTrue(f5.contains("IF INST=HALT GOTO H-1"), f5.toString());
		assertTrue(REV_F.roleOf(REV_F.withTag("J2-1")).notes().stream().anyMatch(n -> n.startsWith("J2-1 MUST BE A NOP")));
		assertTrue(REV_F.roleOf(REV_F.withTag("J2-1A")).notes().stream().noneMatch(n -> n.startsWith("J2-1 MUST BE A NOP")));
	}

	/** A microword the flow leaves out says so, and says where the flow mentions it. */
	@Test
	void aMicrowordNotInTheFlowSaysWhereItIsMentioned() {
		MicrowordRole a145 = REV_F.roleOf(REV_F.withTag("A145"));
		assertNull(a145.routine());
		assertEquals(List.of("Not shown in the flow, which says so on its last page"), a145.notes());

		List<String> ert1a = REV_F.roleOf(REV_F.withTag("ERT1A")).notes();
		assertTrue(ert1a.stream().anyMatch(n -> n.contains("FOR STACK OVERFLOW")
			&& n.contains("ERROR TRAP")), ert1a.toString());
		//-- The priority list after every BUT SERVICE names it; that is one line, not eight.
		assertEquals(1, ert1a.stream().filter(n -> n.startsWith("IF STACK OVERFLOW GOTO ERT1A")).count(), ert1a.toString());
	}

	// -----------------------------------------------------------------------------------------
	// A flow that disagrees
	// -----------------------------------------------------------------------------------------

	/** The flow is a year older than the listing; where they part, the microword says so. */
	@Test
	void aMicrowordTheFlowPutsElsewhereSaysSo() {
		//-- An all-zero NXT is active low and decodes to 377.
		Microcode code = Kd11bMicrocode.parse("test", null, List.of("NAM\tLOC\tWORD40", "X-1\t100\t" + "0".repeat(40)));
		Kd11bFlow flow = Kd11bFlow.parse(List.of("PAGE\t2", "ROUTINE\tTEST\tEXECUTE", "WORD\t100\t101\tX-1\tNOP"));
		assertEquals(1, flow.disagreements(code.byAddress()).size());
		MicrowordRole role = flow.annotate(code.byAddress()).get(0100);
		assertEquals("NOP", role.action());
		assertTrue(role.notes().get(0).startsWith("The flow has this microword at 100 going to 101"), role.notes().toString());
	}

	@Test
	void aMalformedLineIsThrownWithItsLine() {
		IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> Kd11bFlow.parse(
			Arrays.asList("PAGE\t2", "ROUTINE\tTEST\tEXECUTE", "WORD\t100\tX-1\tNOP")));
		assertTrue(x.getMessage().startsWith("kd11b-flow.tsv:3:"), x.getMessage());
	}
}
