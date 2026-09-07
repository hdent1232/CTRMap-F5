package ctrmap.formats.scripts;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * One run of the assembler over one script text: the source, read line by
 * line, and everything the assembler has to say about it. The subroutines are
 * whatever parsed; errors names, by line, every line that did not - a
 * mistyped mnemonic, an argument that is not a number, a casetbl with no
 * closing brace. Diagnostics are values, so the editor reads them instead of
 * redirecting System.out into a text box (which, the first time the assembler
 * threw, stayed redirected for the rest of the session).
 *
 * <p>A script with errors must not be committed. The editor rewrites its text
 * from the assembled instructions, so a line that did not parse would simply
 * vanish - which is exactly what a PSUH_C typo used to do.
 */
public class PawnAssembly {

	public final List<PawnSubroutine> subroutines = new ArrayList<>();
	/** Progress, one entry per subroutine. */
	public final List<String> log = new ArrayList<>();
	/** One entry per source line that did not assemble, each starting "line N:". */
	public final List<String> errors = new ArrayList<>();

	private final Scanner source;
	private int line;
	/**
	 * The script whose natives table a {@code SYSREQ_N} written by native NAME
	 * resolves against, or null when only numeric indices are accepted. Held
	 * here, on the run that needs it, because it is per-script: it used to be
	 * a static on PawnInstruction that the editor set on load and five suites
	 * set and nulled again in a finally, and two scripts assembled in the same
	 * JVM could quietly resolve the same name to each other's index.
	 */
	private final GFLPawnScript natives;

	/** An assembly that accepts natives by index only. */
	public PawnAssembly(String code) {
		this(code, null);
	}

	/** An assembly that also accepts natives by name, resolved in nativesFrom's table. */
	public PawnAssembly(String code, GFLPawnScript nativesFrom) {
		source = new Scanner(code);
		natives = nativesFrom;
	}

	/** natives[] index whose registered name hash matches {@code name}, or -1. */
	int resolveNativeIndex(String name) {
		if (natives == null) {
			return -1;
		}
		int hash = ctrmap.scripts.GfHash.hashForName(name);
		for (int i = 0; i < natives.natives.size(); i++) {
			if (natives.natives.get(i).data[1] == hash) {
				return i;
			}
		}
		return -1;
	}

	boolean hasNextLine() {
		return source.hasNextLine();
	}

	/** The next source line with its indentation removed. */
	String nextLine() {
		line++;
		return source.nextLine().replaceAll("\t", "");
	}

	/** Records that the line just read did not assemble. */
	void error(String what) {
		errors.add("line " + line + ": " + what);
	}

	public int getInstructionCount() {
		int count = 0;
		for (PawnSubroutine sub : subroutines) {
			count += sub.getInstructionCount();
		}
		return count;
	}

	/** The assembler's output as the editor shows it: the log, then every error. */
	public String report() {
		StringBuilder sb = new StringBuilder();
		for (String entry : log) {
			sb.append("[INFO] ").append(entry).append('\n');
		}
		for (String error : errors) {
			sb.append("[ERR] ").append(error).append('\n');
		}
		return sb.toString();
	}
}
