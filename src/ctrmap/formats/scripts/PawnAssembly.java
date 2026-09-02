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

	public PawnAssembly(String code) {
		source = new Scanner(code);
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
