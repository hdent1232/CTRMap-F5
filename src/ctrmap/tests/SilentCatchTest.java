package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many failures this program can still swallow, and a number that may only
 * fall.
 *
 * <p>Not: {@link DialogSeamTest} - that governs how a failure is REPORTED once
 * somebody decides to report it. This counts the places that decide not to: a
 * catch block with nothing in it, or with nothing but a log line, in a program
 * whose whole doctrine is that a silent path is a defect.
 *
 * <p>WHY A CEILING AND NOT A BAN. 119 of them exist today. Some are genuinely
 * nothing-to-do - a close() in a finally, a best-effort repaint - and deciding
 * which, one at a time, is weeks of reading. A ban would be reverted within a
 * day and the doctrine would go back to being decoration. A ceiling seeded at
 * today's measured number costs nothing to keep, refuses the 126th, and turns
 * every one that is fixed into a number that cannot climb back.
 *
 * <p>WHAT IT COUNTS. An EMPTY catch - no statements, comment or not - and a
 * LOG-ONLY catch whose whole body is a logger call, a printStackTrace or a
 * println. Anything that rethrows, sets a flag, returns a failure, or tells the
 * user through {@code Ui} is not counted: it decided something.
 *
 * <p>ORDER: needs no game, no dump and no display; reads source, writes nothing.
 *
 * Usage: java ctrmap.tests.SilentCatchTest [src-root]   (default "src")
 */
public class SilentCatchTest {

	/**
	 * Measured on 2026-09-13: 55 empty and 64 log-only in production sources.
	 *
	 * <p>LOWERED from 125 the same day, by the camera package: three readers and two
	 * writers that logged their IOException and carried on, which is how a camera table
	 * declaring more cameras than it held produced records made of -1s and the editor
	 * offered them. A number that falls is re-seeded here, or the next six cost nothing.
	 *
	 * <p>It may fall and may not rise. Raising it is a decision about whether
	 * this program tells its user when something went wrong, and it should
	 * happen in a commit message where somebody can disagree with it.
	 */
	private static final int CEILING = 119;

	/**
	 * Unchecked {@code read(byte[])} / {@code skip(long)} answers in production: 76,
	 * measured 2026-09-19. It may fall and may not rise.
	 *
	 * <p>THESE ARE SWALLOWED FAILURES TOO, which is why they are counted here rather than
	 * somewhere new. Both primitives answer how much they MANAGED, and a caller that drops
	 * the answer has decided not to notice a short read. On a file-backed stream that means
	 * a zero tail or a wrong offset; the census found twelve of them in the two files every
	 * write goes through - the GARC pack copying entries the user never edited, and the
	 * container rebuilding itself from a buffer it did not fill. That is the class this
	 * project repaired by hand once already, on zone 536. Those twelve are fixed.
	 *
	 * <p>WHY A CEILING AND NOT A BAN, sized before it was written. 76 remain, and most are
	 * {@code skip()} on a ByteArrayInputStream, which cannot read short - banning the
	 * primitive would refuse a large amount of honest code, and a ratchet that fires on
	 * honest work gets its ceiling raised until every ratchet in the tree is decoration.
	 * The static type at the call site does not reliably say whether the stream is
	 * file-backed, so the distinction cannot be drawn mechanically; the number can.
	 */
	private static final int UNCHECKED_READ_CEILING = 76;

	/** A catch block whose body holds no nested braces - the shape we can read. */
	private static final Pattern CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{([^{}]*)\\}");
	/** A body that only writes the failure down somewhere. */
	private static final Pattern LOG_ONLY = Pattern.compile(
			"(?s)\\s*(Logger[^;]*;|[\\w.]*printStackTrace\\(\\);|System\\.(out|err)\\.print[^;]*;)\\s*");

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		System.out.println("--- how many failures this program can still swallow");
		int empty = 0, logOnly = 0;
		List<String> worst = new ArrayList<>();
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			Matcher m = CATCH.matcher(body);
			while (m.find()) {
				String inner = m.group(1);
				String bare = inner.replaceAll("//[^\n]*", "");
				bare = bare.replaceAll("(?s)/\\*.*?\\*/", "").trim();
				if (bare.isEmpty()) {
					empty++;
					//an empty catch with no comment at all is the worst of them: nobody
					//even claimed there was a reason
					if (inner.trim().isEmpty()) {
						worst.add(f.getName() + ":" + lineOf(body, m.start()));
					}
				} else if (LOG_ONLY.matcher(bare).matches()) {
					logOnly++;
				}
			}
		}
		int total = empty + logOnly;
		check(total <= CEILING, total + " silent catch(es) in production code - " + empty
				+ " empty and " + logOnly + " log-only - against a ceiling of " + CEILING
				+ ". This may fall and may not rise: a failure this program swallows is one"
				+ " the user finds out about later, from the game");
		check(!worst.isEmpty() || empty == 0, worst.size() + " of the empty ones have no comment"
				+ " and no named reason at all, which is where to start: " + first(worst, 5));

		uncheckedReadsAndSkips(root);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Counts the {@code read(byte[])} and {@code skip(long)} answers production drops.
	 *
	 * <p>Text, not bytecode, and deliberately: the call site's static type is usually
	 * {@code InputStream} or this project's own wrapper, so the constant pool cannot say
	 * whether the stream underneath is a file or a byte array - which is the only thing
	 * that decides whether a short answer is possible. A count is what can be measured
	 * honestly here, so a count is what is ratcheted.
	 */
	static void uncheckedReadsAndSkips(File root) throws Exception {
		System.out.println("--- read/skip answers this program still drops");
		Pattern call = Pattern.compile(
			"\\.(?:read|skip|skipBytes)\\s*\\(" );
		Pattern safe = Pattern.compile("readFully|skipFully|\\.read\\(\\)");
		List<String> where = new ArrayList<>();
		for (File j : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (j.getParentFile() != null && j.getParentFile().getName().equals("tests")) {
				continue;
			}
			String body = new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8);
			int line = 0;
			for (String text : body.split("\\n")) {
				line++;
				if (call.matcher(text).find() && !safe.matcher(text).find()) {
					where.add(j.getName() + ":" + line);
				}
			}
		}
		check(where.size() <= UNCHECKED_READ_CEILING, where.size() + " unchecked read/skip"
			+ " answer(s) in production, against a ceiling of " + UNCHECKED_READ_CEILING
			+ ". Both primitives report how much they MANAGED; a dropped answer is a short read"
			+ " nobody noticed, which on a file-backed stream is a zero tail or a wrong offset."
			+ " This may fall and may not rise: " + first(where, 5));
		check(!where.isEmpty(), "and the scan still matches something (" + where.size()
			+ ") - a count that has quietly stopped finding anything asserts nothing");
	}

	static String first(List<String> all, int n) {
		return all.size() <= n ? all.toString() : all.subList(0, n) + " (+" + (all.size() - n) + ")";
	}

	static int lineOf(String body, int at) {
		int line = 1;
		for (int i = 0; i < at && i < body.length(); i++) {
			if (body.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
