package to.etc.pdp11.common.diag;

import java.util.List;

/**
 * One diagnostic as DEC's index lists it.
 *
 * @param id        the four letters: {@code ZRLG}
 * @param title     DEC's title, abbreviations and all: "RL11/RLV11/CTRL1"; empty when the index
 *                  gives none
 * @param revisions every revision the index lists, oldest first: {@code D0}, {@code E0}
 * @param packages  the media kits it was shipped in, by their titles: "DDDP52 V2 RL02"
 */
public record CatalogEntry(String id, String title, List<String> revisions, List<String> packages) {
	public CatalogEntry {
		revisions = List.copyOf(revisions);
		packages = List.copyOf(packages);
	}

	public DiagnosticFamily family() {
		return DiagnosticFamily.ofId(id);
	}

	/** The latest revision the index knows, or {@code ""}. */
	public String latestRevision() {
		return revisions.isEmpty() ? "" : revisions.get(revisions.size() - 1);
	}
}
