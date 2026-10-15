package to.etc.pdp11.common.diag;

import to.etc.pdp11.common.diag.media.MediaFile;
import to.etc.pdp11.common.diag.media.MediaReader;
import to.etc.pdp11.common.diag.media.MediaVolume;
import to.etc.pdp11.common.memfile.AbsoluteLoaderTape;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The programs on a disk image, tape image or archive, for the Memory Loader.
 *
 * <p>The same reading the Diagnostics window does when it collects a medium - {@link MediaReader}
 * for the medium, {@link ProgramCheck} for each file - without the library around it: somebody has
 * a {@code xxdp25.rl02} or a {@code .zip} of floppy images on disk and wants one program off it in
 * memory. Every file that is an absolute loader image is offered, the ones that need DRS or the
 * XXDP monitor included, because loading is not running and the person may know what they are
 * doing; the verdict goes with it so they can see.</p>
 */
public final class MediumPrograms {
	/**
	 * One program on the medium.
	 *
	 * @param volume  the medium it is on, which only matters when an archive held several
	 * @param name    {@code NAME.EXT}
	 * @param verdict what {@link ProgramCheck} made of it
	 * @param data    the file's bytes, parsed again when it is loaded: a parsed image is 128 KB and
	 *                an XXDP disk holds hundreds
	 */
	public record Program(String volume, String name, ProgramCheck.Brief verdict, byte[] data) {
		/** Parse it for loading. */
		public AbsoluteLoaderTape tape() throws IOException {
			return AbsoluteLoaderTape.parse(data, name);
		}

		/** One line for a list: name, what it is and, when an archive held more than one medium, where. */
		public String label(boolean showVolume) {
			StringBuilder sb = new StringBuilder(name);
			String kind = verdict.kind().getLabel();
			if(!kind.isEmpty())
				sb.append("  (").append(kind).append(')');
			if(showVolume)
				sb.append("  - ").append(volume);
			return sb.toString();
		}
	}

	/**
	 * @param programs   the loadable programs, in the order the media list them
	 * @param volumes    how many media were found
	 * @param problems   what went wrong reading them, and what was passed over; one sentence each
	 */
	public record Contents(List<Program> programs, int volumes, List<String> problems) {
		public Contents {
			programs = List.copyOf(programs);
			problems = List.copyOf(problems);
		}

		/** Whether the programs come from more than one medium, so that a list should say which. */
		public boolean isSeveralVolumes() {
			return programs.stream().map(Program::volume).distinct().count() > 1;
		}
	}

	private MediumPrograms() {
	}

	/**
	 * @param name the file's name, which is how a {@code .gz}, {@code .zip} or {@code .tap} is
	 *             recognised; disks are recognised by their directory whatever they are called
	 */
	public static Contents read(String name, byte[] data) {
		return read(name, data, DiagnosticCatalog.builtIn());
	}

	static Contents read(String name, byte[] data, DiagnosticCatalog catalog) {
		List<Program> programs = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		List<MediaVolume> volumes = MediaReader.read(name, data);
		for(MediaVolume v : volumes) {
			for(String p : v.problems()) {
				problems.add(v.name() + ": " + p);
			}
			int damaged = 0;
			for(MediaFile f : v.files()) {
				ProgramCheck.Verdict verdict = ProgramCheck.check(f.name(), f.data(), f.damaged(), catalog.identify(f.name()));
				switch(verdict.kind()) {
					case NOT_A_PROGRAM -> {
						//-- Text, chain files, listings: not something to load, and not news.
					}
					case DAMAGED -> damaged++;
					default -> programs.add(new Program(v.name(), f.name(), verdict.brief(), f.data()));
				}
			}
			if(damaged > 0)
				problems.add(v.name() + ": " + damaged + " program" + (damaged == 1 ? " was" : "s were")
					+ " left out because the copy on the medium is incomplete");
		}
		return new Contents(programs, volumes.size(), problems);
	}
}
