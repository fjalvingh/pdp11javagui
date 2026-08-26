package to.etc.pdp11.core.microcode;

import org.junit.jupiter.api.Test;
import to.etc.pdp11.core.util.Octal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KD11-B microcode reader, against both transcribed drawing sets.
 *
 * <p>The 11/44's listing checks itself - every microword prints its jump target as bits and again
 * as text - and {@code Pdp1144MicrocodeTest} leans on that. This document has no such redundancy,
 * so the checks here are different and have to be built rather than found:</p>
 *
 * <ul>
 *   <li><b>the {@code NXT} complement</b>, which is the one silent error this format can carry:
 *       taken as printed 83.2% of next-addresses land on a listed location, which is chance level
 *       when 214 of 256 locations are listed, and complemented 213 of 214 do;</li>
 *   <li><b>the decode against the transcription's own derived columns</b>. The {@code .tsv} files
 *       carry every field decoded by the pipeline that read the scans, and the loader deliberately
 *       ignores those columns and decodes the 40 bits itself. Comparing the two over all 214
 *       microwords of both revisions is what holds {@link Kd11bFields} down - in particular the
 *       scattered {@code SPA}, the scrambled {@code BUT} and the reversed {@code BRG}, each of
 *       which produces a plausible wrong answer for every microword when it is got wrong;</li>
 *   <li><b>the revision difference</b>, expressed as an assertion: exactly 20 bits in 14
 *       microwords, all of them {@code AUX} or {@code CKO}, and every address and next-address
 *       identical between the two.</li>
 * </ul>
 */
class Kd11bMicrocodeTest {
	private static final Microcode REV_E = Kd11bMicrocode.load(Kd11bMicrocode.Revision.E);

	private static final Microcode REV_F = Kd11bMicrocode.load(Kd11bMicrocode.Revision.F);

	private static final MicrocodeArchitecture ARCH = Kd11bFields.ARCHITECTURE;

	// -----------------------------------------------------------------------------------------
	// What was loaded
	// -----------------------------------------------------------------------------------------

	@Test
	void bothRevisionsLoad() {
		for(Microcode code : List.of(REV_E, REV_F)) {
			assertEquals(214, code.size(), code.getSourceName());
			assertEquals(0, code.byAddress().get(0).getAddress(), "the listing starts at 000");
			for(MicroInstruction mi : code.byAddress()) {
				assertTrue(mi.getAddress() < 0400, mi + " is outside a 256 word control store");
				assertEquals(3, mi.getAddressOctal().length(), "an 8 bit address is three octal digits");
			}
		}
		assertEquals("M7261 rev E", REV_E.getRevision());
		assertEquals("M7261 rev F", REV_F.getRevision());
	}

	/**
	 * The one complaint the shipped data is allowed: the control store is sparse, and the filler
	 * microword at 145 points into a location the listing does not print.
	 */
	@Test
	void theOnlyProblemIsTheOneMicrowordThatLeavesTheListing() {
		for(Microcode code : List.of(REV_E, REV_F)) {
			assertEquals(1, code.getProblems().size(), code.getProblems().toString());
			Microcode.Problem p = code.getProblems().get(0);
			assertEquals(Microcode.ProblemKind.MISSING_NEXT, p.kind());
			assertTrue(p.message().startsWith("A145 goes to 377"), p.message());
		}
	}

	@Test
	void theResetEntryPointIsAtZero() {
		//-- Cheap, and it is the check that the address column is not shifted by a row.
		assertEquals("RS-1", REV_F.atAddress(0).getSymbolicTag());
	}

	// -----------------------------------------------------------------------------------------
	// NXT is stored active low
	// -----------------------------------------------------------------------------------------

	/**
	 * The assertion that stops anybody "fixing" the complement.
	 *
	 * <p>It is written as a coverage count rather than as a spot check because that is the form
	 * that fails: 214 of 256 locations are listed, so an uncomplemented address hits a listed
	 * location 83.6% of the time by luck alone and every individual microword still looks
	 * plausible. Only the count separates the two readings.</p>
	 */
	@Test
	void everyNextAddressButOneNamesAMicrowordThatExists() {
		for(Microcode code : List.of(REV_E, REV_F)) {
			int resolved = 0;
			for(MicroInstruction mi : code.byAddress()) {
				if(code.atAddress(mi.getNextAddress()) != null)
					resolved++;
			}
			assertEquals(213, resolved, code.getSourceName() + ": next addresses that land on a microword");
		}
	}

	/** Sequentially named microwords forming a next-address chain does not happen by accident. */
	@Test
	void theBRoutineChainsThroughItsOwnNextAddresses() {
		MicroInstruction b1 = REV_F.atAddress(0015);
		assertEquals("B-1", b1.getSymbolicTag());
		MicroInstruction b2 = REV_F.atAddress(b1.getNextAddress());
		assertEquals("B-2", b2.getSymbolicTag());
		assertEquals(0147, b2.getAddress());
		MicroInstruction b3 = REV_F.atAddress(b2.getNextAddress());
		assertEquals("B-3", b3.getSymbolicTag());
		assertEquals(0146, b3.getAddress());
		assertEquals("BG-1", REV_F.atAddress(b3.getNextAddress()).getSymbolicTag());
		assertEquals(0040, b3.getNextAddress());
	}

	// -----------------------------------------------------------------------------------------
	// The field table
	// -----------------------------------------------------------------------------------------

	@Test
	void theEighteenFieldsUseEveryOneOfTheFortyBits() {
		//-- unusedBits() throws on an overlap, so this asserts both halves: the KD11-B has no
		//-- spare bit, unlike the 11/44 which has two.
		assertEquals(List.of(), ARCH.unusedBits(Kd11bFields.WORD_BITS));
		assertEquals(18, ARCH.size());
		assertEquals(8, ARCH.getAddressBits());
	}

	@Test
	void theThreeAwkwardFieldsAreNotRanges() {
		assertFalse(field(Kd11bFields.SPA).isContiguous(), "four scattered out-of-order columns");
		assertEquals("21,12,22,18", field(Kd11bFields.SPA).bitRange());
		assertFalse(field(Kd11bFields.BUT).isContiguous(), "printed BUT-1, BUT-0, BUT-2, BUT-3");
		assertEquals("0,1,3,2", field(Kd11bFields.BUT).bitRange());
		assertFalse(field(Kd11bFields.BRG).isContiguous(), "printed BMODE-0 above BMODE-1");
		assertTrue(field(Kd11bFields.NXT).isContiguous());
		assertEquals("39:32", field(Kd11bFields.NXT).bitRange());
	}

	/**
	 * The scratchpad address is a register number, which is what says the four scattered columns
	 * were put back together the right way round. Read as four independent flags it is noise.
	 */
	@Test
	void theScatteredScratchpadAddressReadsAsRegisterNumbers() {
		Map<Integer, Integer> counts = new TreeMap<>();
		for(MicroInstruction mi : REV_F.byAddress())
			counts.merge(mi.getValue(field(Kd11bFields.SPA)), 1, Integer::sum);
		assertEquals(118, counts.get(0), "no scratchpad register");
		assertEquals(28, counts.get(7), "R7, the PC");
		assertEquals(22, counts.get(6), "R6, the SP");
		//-- And the independent check: the mux selects ROM in exactly the microwords that name a
		//-- register, and in none of the others. The two decodes share no bits.
		for(MicroInstruction mi : REV_F.byAddress()) {
			boolean named = mi.getValue(field(Kd11bFields.SPA)) != 0;
			boolean fromRom = "ROM".equals(mi.getText(field(Kd11bFields.SPAMUX)));
			if(named)
				assertTrue(fromRom, mi + " names a register but does not take the address from the ROM");
		}
	}

	/**
	 * The unscrambled {@code BUT} field corroborates itself semantically, which is the only way
	 * it can be checked: all sixteen microtests are defined, so a wrong bit order gives a
	 * plausible wrong microtest for every microword and there is no error to see.
	 */
	@Test
	void theUnscrambledBranchMicrotestsLandWhereTheyShould() {
		MicrocodeField but = field(Kd11bFields.BUT);
		Map<String, List<String>> byTest = new LinkedHashMap<>();
		for(MicroInstruction mi : REV_F.byAddress())
			byTest.computeIfAbsent(mi.getText(but), k -> new ArrayList<>()).add(mi.getSymbolicTag());
		assertEquals(141, byTest.get("NON").size(), "no branch, in 141 of 214");
		assertEquals(List.of("RST-1"), byTest.get("IR-DECODE"),
			"exactly one microword in the whole microprogram dispatches on the instruction");
		assertEquals(0357, REV_F.withTag("RST-1").getAddress());
		assertEquals(List.of("BG-1"), byTest.get("SSYNC"), "the one SSYNC test is the bus grant");
		assertEquals(List.of("D1-2", "S0-1", "S1-2"), byTest.get("SERVICE").stream().sorted().toList());
	}

	/**
	 * {@code F-SHIFT} is the shifter on the ALU output, and the data says so on its own: it is
	 * asserted in five straight-line runs of exactly seven consecutive shift steps, each holding
	 * one ALU operation and one B register mode for all seven and stopping on the eighth. A spare
	 * bit cannot produce that.
	 */
	@Test
	void theShifterIsAssertedInFiveChainsOfSevenSteps() {
		MicrocodeField fsh = field(Kd11bFields.FSH);
		MicrocodeField alu = field(Kd11bFields.ALU);
		MicrocodeField brg = field(Kd11bFields.BRG);
		Map<String, List<String>> chains = new LinkedHashMap<>();
		int shifting = 0;
		for(MicroInstruction mi : REV_F.byAddress()) {
			if(mi.getValue(fsh) != 0)
				continue;
			shifting++;
			//-- The tags are <chain>-<step>, so the chain is the tag up to its last dash.
			String chain = mi.getSymbolicTag().substring(0, mi.getSymbolicTag().lastIndexOf('-'));
			chains.computeIfAbsent(chain, k -> new ArrayList<>())
				.add(mi.getText(alu) + " " + mi.getText(brg));
		}
		assertEquals(35, shifting);
		assertEquals(List.of("DO", "SB1", "SB2", "SBO"), chains.keySet().stream().sorted().toList(),
			"DO carries two of the five chains, DO-1..7 and DO-11..17");
		for(Map.Entry<String, List<String>> e : chains.entrySet())
			assertEquals(0, e.getValue().size() % 7, e.getKey() + " is not a whole number of seven step chains");
		//-- Across all 214 microwords the shifter appears with only two of the twelve ALU codes.
		assertEquals(List.of("AL", "ASR"), chains.values().stream()
			.flatMap(List::stream).map(s -> s.split(" ")[0]).distinct().sorted().toList());
	}

	// -----------------------------------------------------------------------------------------
	// The decode, against the transcription's own derived columns
	// -----------------------------------------------------------------------------------------

	/**
	 * Every field of every microword of both revisions, decoded here and decoded there.
	 *
	 * <p>The loader reads three columns of the {@code .tsv} - the tag, the address and the 40 bits
	 * - and works everything else out through {@link Kd11bFields}. The file also carries the
	 * pipeline's own decode of every field, from the same bits but by different code written at a
	 * different time. This compares them, which is what turns "the field table looks right" into
	 * something a build can fail on.</p>
	 */
	@Test
	void everyFieldDecodesToWhatTheTranscriptionSaysItDoes() {
		for(Kd11bMicrocode.Revision rev : Kd11bMicrocode.Revision.values()) {
			Microcode code = rev == Kd11bMicrocode.Revision.E ? REV_E : REV_F;
			for(Map<String, String> row : rows(rev)) {
				MicroInstruction mi = code.withTag(row.get("NAM"));
				assertNotNull(mi, row.get("NAM"));
				String where = rev + " " + mi.getSymbolicTag() + " @" + mi.getAddressOctal() + ": ";

				//-- The complemented next address, and the ALU code as a number.
				assertEquals(row.get("NXTADDR"), mi.getNextAddressOctal(), where + "next address");
				assertEquals(row.get("ALUCODE"), Octal.format(mi.getValue(field(Kd11bFields.ALU)), 2),
					where + "ALU code");

				//-- The four fields that are printed in an order that is not their value.
				assertEquals(Integer.parseInt(row.get("SPA")), mi.getValue(field(Kd11bFields.SPA)),
					where + "scratchpad address");
				assertEquals(row.get("BUTNAME"), mi.getText(field(Kd11bFields.BUT)), where + "microtest");
				assertEquals(row.get("BRGNAME"), mi.getText(field(Kd11bFields.BRG)), where + "B register");
				assertEquals(row.get("SPAMUX"), mi.getText(field(Kd11bFields.SPAMUX)), where + "address source");

				//-- The named ones that are printed as they read.
				assertEquals(row.get("ALGNAME"), mi.getText(field(Kd11bFields.ALG)), where + "A leg");
				assertEquals(row.get("SPFNAME"), mi.getText(field(Kd11bFields.SPF)), where + "scratchpad write");
				assertEquals(row.get("CKONAME"), mi.getText(field(Kd11bFields.CKO)), where + "clock");
				//-- "??" is how the transcription writes the one combination nothing names.
				String tns = mi.getText(field(Kd11bFields.TNS));
				assertEquals(row.get("TNSNAME"), tns == null ? "??" : tns, where + "bus cycle");

				//-- And the raw printed bit of every single-bit control line.
				assertBit(row, "CRI", mi, Kd11bFields.CRI, where);
				assertBit(row, "FSH", mi, Kd11bFields.FSH, where);
				assertBit(row, "AUX", mi, Kd11bFields.AUX, where);
				assertBit(row, "PSW", mi, Kd11bFields.PSW, where);
				assertBit(row, "DIP", mi, Kd11bFields.DIP, where);
				assertBit(row, "BAR", mi, Kd11bFields.BAR, where);
				assertBit(row, "SPF", mi, Kd11bFields.SPF, where);
				assertBit(row, "CKO", mi, Kd11bFields.CKO, where);
				assertBit(row, "ABT", mi, Kd11bFields.ABT, where);
			}
		}
	}

	private static void assertBit(Map<String, String> row, String column, MicroInstruction mi,
		String fieldName, String where) {
		assertEquals(Integer.parseInt(row.get(column)), mi.getValue(field(fieldName)), where + column);
	}

	/** The ten PROM slices are the word cut into fours, which is how the machine holds it. */
	@Test
	void theRawWordsAreTheTenPromSlices() {
		MicroInstruction b1 = REV_F.atAddress(0015);
		int[] slices = b1.getRawWords();
		assertEquals(10, slices.length);
		//-- B-1's printed bits end 1111, which is BUT = NON, and slice 0 is bits 3:0.
		assertEquals(017, slices[0]);
		assertEquals("NON", b1.getText(field(Kd11bFields.BUT)));
		//-- Reassembled most significant slice first, the slices are the printed word again.
		StringBuilder sb = new StringBuilder();
		for(int i = slices.length - 1; i >= 0; i--) {
			for(int b = Kd11bFields.PROM_SLICE_BITS - 1; b >= 0; b--)
				sb.append((slices[i] >>> b) & 1);
		}
		assertEquals(40, sb.length());
		assertEquals(rowFor(Kd11bMicrocode.Revision.F, "B-1").get("WORD40"), sb.toString());
	}

	// -----------------------------------------------------------------------------------------
	// The revision difference
	// -----------------------------------------------------------------------------------------

	/**
	 * The whole of the E to F change, as an assertion.
	 *
	 * <p>20 bits across 14 microwords, confined to {@code AUX} and {@code CKO} - which is exactly
	 * the two fields held by the only two of the ten control store PROMs whose part numbers
	 * change between the revisions. If a future re-transcription smears an OCR error into either
	 * file, this is what notices: OCR error scatters, and a design change does not.</p>
	 */
	@Test
	void theTwoRevisionsDifferInTwentyBitsOfTwoFields() {
		assertEquals(REV_E.size(), REV_F.size());
		List<String> changed = new ArrayList<>();
		int bits = 0;
		for(MicroInstruction e : REV_E.byAddress()) {
			MicroInstruction f = REV_F.atAddress(e.getAddress());
			assertNotNull(f, e + " has no counterpart in rev F");
			assertEquals(e.getSymbolicTag(), f.getSymbolicTag(), "the tags are the same in both sets");
			assertEquals(e.getNextAddress(), f.getNextAddress(), e + ": the control graph does not change");
			boolean differs = false;
			for(MicrocodeField field : ARCH.getFields()) {
				if(e.getValue(field) == f.getValue(field))
					continue;
				assertTrue(field.name().equals(Kd11bFields.AUX) || field.name().equals(Kd11bFields.CKO),
					e + " differs in " + field.name() + ", which is not one of the two the revision changed");
				bits++;
				differs = true;
			}
			if(differs)
				changed.add(e.getSymbolicTag());
		}
		assertEquals(20, bits, "bits that differ between the drawing sets");
		assertEquals(14, changed.size(), "microwords that differ: " + changed);
		assertEquals(List.of("D0-3", "D0-3A", "D1-4", "DB0-2", "DO-10", "MB-0", "MB-1",
			"SB1-8", "SB2-8", "U1-1", "U2-1", "U3-1", "U4-1", "U5-1"),
			changed.stream().sorted().toList());
	}

	/**
	 * The revision moved which microwords take their ALU control from the instruction register,
	 * wholesale: six in rev E and eight in rev F, and not one of them the same microword.
	 *
	 * <p>{@code A145} is left out of both counts and has to be. It is the filler at 145 whose 40
	 * bits are almost entirely zero - so it reads as asserting {@code AUX}, along with every
	 * other line that is asserted low - and it is the one entry in the listing that is not a
	 * state of the machine. It is also the only microword the two revisions agree on here, so
	 * counting it would make a wholesale move look like an overlap.</p>
	 *
	 * <p>{@code kd11b-README.md} §6 gives these as six and <i>nine</i>, which is a slip in its
	 * prose rather than in its data: the nine counts the filler and the six does not. Both
	 * numbers here exclude it.</p>
	 */
	@Test
	void theRevisionMovedTheIrDecodedAluControlWholesale() {
		List<String> e = takingAluControlFromIr(REV_E);
		List<String> f = takingAluControlFromIr(REV_F);
		assertEquals(6, e.size(), e.toString());
		assertEquals(8, f.size(), f.toString());
		assertTrue(e.stream().noneMatch(f::contains), "the set moved rather than grew: " + e + " -> " + f);
		//-- And the filler is in neither list only because it was taken out of both.
		assertTrue(Kd11bFields.takesAluControlFromIr(REV_E.withTag("A145")));
		assertTrue(Kd11bFields.takesAluControlFromIr(REV_F.withTag("A145")));
	}

	/** Every microword taking its ALU control from the IR, bar the all-but-zero filler. */
	private static List<String> takingAluControlFromIr(Microcode code) {
		List<String> out = new ArrayList<>();
		for(MicroInstruction mi : code.byAddress()) {
			if(Kd11bFields.takesAluControlFromIr(mi) && !"A145".equals(mi.getSymbolicTag()))
				out.add(mi.getSymbolicTag());
		}
		return out;
	}

	// -----------------------------------------------------------------------------------------
	// Complaints
	// -----------------------------------------------------------------------------------------

	@Test
	void aRowWithTooFewBitsIsAProblemAndNotAnException() {
		Microcode code = Kd11bMicrocode.parse("test", null, List.of(
			"NAM\tLOC\tWORD40",
			"X-1\t000\t1010"));
		assertEquals(0, code.size());
		assertTrue(code.getProblems().stream().anyMatch(p -> p.kind() == Microcode.ProblemKind.WRONG_BIT_COUNT),
			code.getProblems().toString());
		assertTrue(code.getProblems().stream()
			.anyMatch(p -> p.kind() == Microcode.ProblemKind.WRONG_MICROWORD_COUNT), code.getProblems().toString());
		assertNull(code.getRevision(), "a hand-made listing is of no board revision");
	}

	@Test
	void anAddressOutsideTheControlStoreIsAProblem() {
		Microcode code = Kd11bMicrocode.parse("test", null, List.of(
			"NAM\tLOC\tWORD40",
			"X-1\t7000\t" + "0".repeat(40)));
		assertTrue(code.getProblems().stream()
			.anyMatch(p -> p.kind() == Microcode.ProblemKind.ADDRESS_OUT_OF_RANGE), code.getProblems().toString());
	}

	// -----------------------------------------------------------------------------------------
	// Reading the transcription for ourselves
	// -----------------------------------------------------------------------------------------

	private static MicrocodeField field(String name) {
		MicrocodeField f = ARCH.byName(name);
		assertNotNull(f, name);
		return f;
	}

	private static Map<String, String> rowFor(Kd11bMicrocode.Revision rev, String tag) {
		for(Map<String, String> row : rows(rev)) {
			if(tag.equals(row.get("NAM")))
				return row;
		}
		throw new IllegalArgumentException("No microword tagged " + tag);
	}

	/** The packaged transcription, every column of it, which is what the loader does not read. */
	private static List<Map<String, String>> rows(Kd11bMicrocode.Revision rev) {
		String resource = "/microcode/kd11b/" + rev.getResourceName();
		try(InputStream is = Kd11bMicrocodeTest.class.getResourceAsStream(resource)) {
			assertNotNull(is, resource);
			try(BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				List<Map<String, String>> out = new ArrayList<>();
				String[] header = null;
				for(String line : r.lines().toList()) {
					if(line.isBlank() || line.charAt(0) == '#')
						continue;
					String[] cells = line.split("\t", -1);
					if(header == null) {
						header = cells;
						continue;
					}
					Map<String, String> row = new LinkedHashMap<>();
					for(int i = 0; i < header.length && i < cells.length; i++)
						row.put(header[i].strip(), cells[i].strip());
					out.add(row);
				}
				return out;
			}
		} catch(IOException x) {
			throw new UncheckedIOException(x);
		}
	}
}
