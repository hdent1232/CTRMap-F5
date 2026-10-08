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
 * user through {@code Ui} is not counted: it decided something. A catch that
 * answers a DEFAULT - null, false, -1, empty, continue - after a log line or
 * instead of one is counted apart (F211): those decided the wrong thing, that
 * "could not read" means "absent". Every count is held EQUAL to its ceiling.
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
	//TIGHTENED 119 -> 118 on 2026-09-20. The measured count was 118, so the ceiling
	//had ONE FREE SLOT - and a plant that added a silent catch landed in it and
	//SURVIVED. A ratchet only bites while it is kept tight: every slot of slack is
	//a defect of exactly that size that the guard will not notice.
	//TIGHTENED 118 -> 113 on 2026-10-07, measured by this suite: 118 (55 empty, 63
	//log-only) on a93ef21's source, 113 (55 empty, 58 log-only) once GRProp's reader and
	//writer and GRPropData's reader stopped logging and carrying on (F083), with two
	//more gone earlier in the same batch.
	//TIGHTENED 113 -> 93 on 2026-10-07, measured by this suite: 93 (54 empty, 39 log-only) after
	//the batch that made the camera form, the tileset editor and its reader, the text editor, the
	//building catalog and the stream handles say what they had logged.
	//TIGHTENED 93 -> 55 on 2026-10-07, measured by this suite: 55 (23 empty, 32 log-only). Eleven
	//of the 38 were slack (82 measured, once a comment quoting old code stopped counting as a
	//catch); the other 27 are F211's - the 24 empty catches with no word of reason, Check for
	//updates' commented one, and the donor-texture and ground-material loaders' stderr lines, each
	//now said, rethrown or gone. HELD EQUAL from today, like the substitute ceiling: the 2026-09-20
	//note above is what slack costs, and "may fall" let eleven slots of it build up again.
	//TIGHTENED 55 -> 50 on 2026-10-08, measured by this suite: the native-name table, the gizmo's
	//arrows and the bundled-resource loaders refuse in words instead of logging (F130), and the
	//rest of the 2026-10-06 audit's low fixes.
	//TIGHTENED 50 -> 48 on 2026-10-08, measured by this suite on the trees between: TerrainHarvester
	//went (F119) and the two injectors' donor searches became one (F087).
	private static final int CEILING = 48;

	/**
	 * Unchecked {@code read(byte[])} / {@code skip(long)} answers in production: 50,
	 * measured 2026-10-07 once a static parser's {@code X.read(...)} stopped being
	 * counted as a stream's answer (76 measured 2026-09-19, 24 of them such calls, and
	 * two stream calls gone with the zone save's RandomAccessFile). It may fall and may
	 * not rise.
	 *
	 * <p>THESE ARE SWALLOWED FAILURES TOO, which is why they are counted here rather than
	 * somewhere new. Both primitives answer how much they MANAGED, and a caller that drops
	 * the answer has decided not to notice a short read. On a file-backed stream that means
	 * a zero tail or a wrong offset; the census found twelve of them in the two files every
	 * write goes through - the GARC pack copying entries the user never edited, and the
	 * container rebuilding itself from a buffer it did not fill. That is the class this
	 * project repaired by hand once already, on zone 536. Those twelve are fixed.
	 *
	 * <p>WHY A CEILING AND NOT A BAN, sized before it was written. 76 remained when it was set, and most are
	 * {@code skip()} on a ByteArrayInputStream, which cannot read short - banning the
	 * primitive would refuse a large amount of honest code, and a ratchet that fires on
	 * honest work gets its ceiling raised until every ratchet in the tree is decoration.
	 * The static type at the call site does not reliably say whether the stream is
	 * file-backed, so the distinction cannot be drawn mechanically; the number can.
	 */
	//TIGHTENED 50 -> 35 on 2026-10-07, measured by this suite: 35 once the six available()-sized
	//reads became whole-file reads (F131), the handles closed only on success went into
	//try-with-resources (F254) and the GARC pack copied untouched entries with readFully (F055).
	//The count leads the message no longer, so a plant can quote the message and not the count.
	//TIGHTENED 35 -> 32 on 2026-10-07, measured by this suite: two were slack, and Bytes.checkMagicLE16
	//reads its two bytes one at a time now that it throws on a file it cannot read (F211). Held equal.
	//TIGHTENED 32 -> 28 on 2026-10-08, measured by this suite: the material decoder seeks to what it
	//reads instead of reading and skipping what nothing draws (F079), and the commented-out C# went.
	//TIGHTENED 28 -> 27 on 2026-10-08, measured by this suite: WavefrontOBJ went into the one OBJ
	//reader (F070), and with it the line that called its own read method, which the text rule counted.
	//RE-SEEDED 27 -> 12 on 2026-10-08, measured by this suite after the rule changed subject (F212): it
	//counted LINES, so 14 reads whose count is returned, assigned or tested in a loop counted the same as
	//a dropped one, and a codec's void read(...) did too (renamed decode). 12 are statement-level calls
	//on a stream whose answer goes nowhere, each read off the probe's list.
	//TIGHTENED 12 -> 10 on 2026-10-08, measured by this suite: a camera's first sixteen bytes are read
	//whole (F221), and the BCH reader is a cursor over its array that skips no stream (F228).
	private static final int UNCHECKED_READ_CEILING = 10;

	/**
	 * Catch blocks that ANSWER A DEFAULT and say nothing else (F211): return null, false, -1, 0,
	 * "", an empty array or collection, continue, break, or a literal assigned - after a log line
	 * or instead of one. The two counts above cannot see them by construction, and these are the
	 * catches that DECIDE: they turn "could not read" into "absent", "empty", "not shared". Some
	 * are a parse probe's documented answer, so this is a ceiling, not a ban - held EQUAL to what
	 * is measured, so each one fixed lowers it and none can come back unnoticed.
	 *
	 * <p>WHY NOT AN ALLOWLIST of the argued ones, as the finding offered: an entry keyed by file
	 * and line moves with every edit above it, and one keyed by method name survives the method
	 * growing a second, unargued catch. The count is what can be held honestly.
	 */
	//SEEDED at 84 on 2026-10-07, measured by this suite: 90 the first time it ran, 84 once the
	//update check, the zone mini-pack probe, the painter's preview and the batch beside them said
	//what they had answered a default for (F211).
	//TIGHTENED 84 -> 79 on 2026-10-08, measured by this suite on the trees between: TerrainHarvester
	//went, and with it five catches that answered a default (F119).
	//HELD at 79 through F248 on 2026-10-08, the two trees' lists diffed with this suite's classifier:
	//the three donor loaders (two in NPCEditForm, one in the painter) became PawnClosure.zoneScripts,
	//one catch for three (-2); and MapMatrix.firstRegionId and WorkspaceIntegrity's matrix pass read
	//through the Grid now, whose refusal of a grid that does not fit turns into what each already
	//answered - the -1 MapDefaultsTest pins, a matrix the pass leaves alone - which they did before by
	//an `if`, out of this count's sight (+2). The same answers, now counted.
	private static final int SUBSTITUTE_CEILING = 79;

	/** A statement that answers a default. */
	private static final Pattern DEFAULT = Pattern.compile("(return\\s*(null|false|true|-1|0|0L|0f|\"\"|new\\s+[\\w.<>\\[\\]]+\\s*\\[\\s*0\\s*\\]"
			+ "|new\\s+\\w+(<[^>]*>)?\\s*\\(\\s*\\)|(java\\.util\\.)?Collections\\.\\w+\\(\\)|[\\w.]*(EMPTY|NONE|NO_\\w+)\\w*)?\\s*;"
			+ "|continue\\s*;|break\\s*;|[\\w.\\[\\]]+\\s*=\\s*(null|false|true|-1|0|\"\"|new\\s+[\\w.<>]+\\s*\\(\\s*\\)"
			+ "|new\\s+[\\w.<>]+\\[\\s*0\\s*\\])\\s*;)");
	/** A statement that only writes the failure down. */
	private static final Pattern LOG_STATEMENT = Pattern.compile(
			"(?s)(Logger\\..*;|[\\w.]*printStackTrace\\(\\);|System\\.(out|err)\\.print.*;|[\\w.]*log\\(.*\\);)");
	/** A body that tells somebody, or hands the failure on. */
	private static final Pattern REPORTS = Pattern.compile("\\bthrow\\b|Ui\\.|JOptionPane|\\.setText\\(|status\\.|error\\("
			+ "|message\\(|warn\\(|refus|report|errors?\\.add|problems?\\.add|skipped\\.add|why\\w*\\s*=|\\.add\\(.*(reason|ex|e\\.getMessage)");

	/**
	 * Every production catch block, brace-aware, in text whose comments and literal contents are
	 * blanked; the catches that answer a default are counted against {@link #SUBSTITUTE_CEILING}.
	 */
	static void substitutingCatches(File root) throws Exception {
		System.out.println("--- catches that answer a default and say nothing (F211)");
		String known = "class A {\n void a() { try { x(); } catch (E e) { return null; } }\n"
				+ " int b() { try { x(); } catch (E e) { log(e); return -1; } }\n"
				+ " int c() { try { x(); } catch (E e) { Ui.error(p, \"no\", \"t\"); return -1; } }\n"
				+ " int d() { try { x(); } catch (E e) { if (y) { return 0; } } }\n"
				+ " void e() { try { x(); } catch (E e) { } }\n"
				+ " String f() { try { x(); } catch (E e) { return \"} not a brace\".trim(); } return null; }\n}\n";
		List<String> kinds = new ArrayList<>();
		for (int[] c : catchKinds(known)) {
			kinds.add(c[0] + ":" + KIND[c[1]]);
		}
		check(kinds.equals(java.util.Arrays.asList("2:substitute", "3:substitute", "4:other", "5:other", "6:empty", "7:other")),
				"control: a bare default and a logged one are counted; a report, a nested block, an empty catch and"
				+ " a brace inside a string are told apart: " + kinds);
		List<String> found = new ArrayList<>();
		int files = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			files++;
			for (int[] c : catchKinds(DialogSeamTest.read(f))) {
				if (c[1] == 2) {
					found.add(f.getName() + ":" + c[0]);
				}
			}
		}
		check(found.size() <= SUBSTITUTE_CEILING, "catch(es) answering a default in " + files + " production files: "
				+ found.size() + ", against a ceiling of " + SUBSTITUTE_CEILING + (found.size() <= SUBSTITUTE_CEILING ? ""
				: ". The newest kind of 'could not read' turned into 'absent': " + found));
		check(found.size() == SUBSTITUTE_CEILING, "and the default-substitute ceiling is what is measured (measured "
				+ found.size()
				+ ", recorded " + SUBSTITUTE_CEILING + ")" + (found.size() < SUBSTITUTE_CEILING
				? " - lower it: a slot of slack is a defect the ratchet will not notice" : ""));
	}

	static final String[] KIND = {"empty", "log-only", "substitute", "other"};

	/** {line, kind} of every catch block in {@code raw}; kind indexes {@link #KIND}. */
	static List<int[]> catchKinds(String raw) {
		String code = blankLiterals(raw);
		List<int[]> out = new ArrayList<>();
		Matcher m = Pattern.compile("\\bcatch\\s*\\(").matcher(code);
		while (m.find()) {
			int open = code.indexOf('{', code.indexOf(')', m.end()));
			if (open < 0) {
				continue;
			}
			int depth = 1, k = open + 1;
			while (k < code.length() && depth > 0) {
				char c = code.charAt(k++);
				depth += c == '{' ? 1 : c == '}' ? -1 : 0;
			}
			String body = code.substring(open + 1, Math.max(open + 1, k - 1)).trim();
			List<String> statements = new ArrayList<>();
			for (String s : body.split(";")) {
				if (!s.trim().isEmpty()) {
					statements.add(s.trim().replaceAll("\\s+", " ") + ";");
				}
			}
			int kind;
			if (statements.isEmpty()) {
				kind = 0;
			} else if (allMatch(statements, LOG_STATEMENT, null)) {
				kind = 1;
			} else if (!REPORTS.matcher(body).find() && body.indexOf('{') < 0
					&& DEFAULT.matcher(statements.get(statements.size() - 1)).matches()
					&& allMatch(statements, LOG_STATEMENT, DEFAULT)) {
				kind = 2;
			} else {
				kind = 3;
			}
			out.add(new int[]{lineOf(raw, m.start()), kind});
		}
		return out;
	}

	static boolean allMatch(List<String> statements, Pattern a, Pattern b) {
		for (String s : statements) {
			if (!a.matcher(s).matches() && (b == null || !b.matcher(s).matches())) {
				return false;
			}
		}
		return true;
	}

	/** {@code src} with comments and the insides of string and char literals turned to spaces, newlines kept. */
	static String blankLiterals(String src) {
		StringBuilder out = new StringBuilder(src.length());
		int i = 0, n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (c == '"' || c == '\'') {
				out.append(c);
				i++;
				while (i < n && src.charAt(i) != c) {
					boolean esc = src.charAt(i) == '\\';
					out.append(src.charAt(i) == '\n' ? '\n' : ' ');
					i++;
					if (esc && i < n) {
						out.append(' ');
						i++;
					}
				}
				if (i < n) {
					out.append(c);
					i++;
				}
			} else if (src.startsWith("//", i)) {
				while (i < n && src.charAt(i) != '\n') {
					out.append(' ');
					i++;
				}
			} else if (src.startsWith("/*", i)) {
				int end = src.indexOf("*/", i + 2);
				end = end < 0 ? n : end + 2;
				for (; i < end; i++) {
					out.append(src.charAt(i) == '\n' ? '\n' : ' ');
				}
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	/** A catch block whose body holds no nested braces - the shape we can read. */
	private static final Pattern CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{([^{}]*)\\}");
	/** A body that only writes the failure down somewhere. */
	private static final Pattern LOG_ONLY = Pattern.compile(
			"(?s)\\s*(Logger[^;]*;|[\\w.]*printStackTrace\\(\\);|System\\.(out|err)\\.print[^;]*;)\\s*");

	static int fails = 0;

	/**
	 * One source's silent catches: {empty, log-only}, each empty one with no word of reason added to
	 * {@code worst} by file and line.
	 *
	 * <p>MATCHED ON CODE, READ FROM SOURCE. The pattern ran on the raw text, so a comment quoting old
	 * code - "catch (Exception ignore) {}" in ZoneLoadingPanel - was counted as an empty catch with no
	 * reason. Blanking keeps every offset, so each body is still read from the raw text and its own
	 * comments still count as a reason.
	 */
	static int[] silentCatches(String body, String name, List<String> worst) {
		int empty = 0, logOnly = 0;
		String code = blankLiterals(body);
		Matcher m = CATCH.matcher(code);
		while (m.find()) {
			String inner = body.substring(m.start(1), m.end(1));
			String bare = code.substring(m.start(1), m.end(1)).trim();
			if (bare.isEmpty()) {
				empty++;
				//an empty catch with no comment at all is the worst of them: nobody
				//even claimed there was a reason
				if (inner.trim().isEmpty()) {
					worst.add(name + ":" + lineOf(body, m.start()));
				}
			} else if (LOG_ONLY.matcher(bare).matches()) {
				logOnly++;
			}
		}
		return new int[]{empty, logOnly};
	}

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		System.out.println("--- how many failures this program can still swallow");
		//the rule's own known answers, before its count is believed. The comment it was written for -
		//ZoneLoadingPanel quoting "catch (Exception ignore) {}" - left the tree, and with it the only
		//thing that could tell a quoted catch from a counted one (F248's re-drive)
		List<String> knownWorst = new ArrayList<>();
		int[] quoted = silentCatches("class A {\n\t//old: try { f(); } catch (Exception ignore) {}\n\tvoid f() {\n\t}\n}\n",
				"Quoted.java", knownWorst);
		int[] real = silentCatches("class A {\n\tvoid f() {\n\t\ttry { g(); } catch (Exception ignore) {}\n\t}\n}\n",
				"Real.java", knownWorst);
		check(quoted[0] + quoted[1] == 0 && real[0] == 1 && knownWorst.equals(java.util.Collections.singletonList("Real.java:3")),
				"the rule's known answers: a catch quoted in a comment is not counted, the same catch in code is, and is"
				+ " named by its line: " + knownWorst);
		int empty = 0, logOnly = 0;
		List<String> worst = new ArrayList<>();
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			int[] found = silentCatches(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), f.getName(), worst);
			empty += found[0];
			logOnly += found[1];
		}
		int total = empty + logOnly;
		check(total <= CEILING, total + " silent catch(es) in production code - " + empty
				+ " empty and " + logOnly + " log-only - against a ceiling of " + CEILING
				+ ". This may fall and may not rise: a failure this program swallows is one"
				+ " the user finds out about later, from the game");
		check(total == CEILING, "and the silent-catch ceiling is what is measured (measured " + total + ", recorded "
				+ CEILING + ")"
				+ (total < CEILING ? " - lower it: a slot of slack is a defect the ratchet will not notice" : ""));
		//NOT INVERTED (F211, census M.82): this read "!worst.isEmpty() || empty == 0", which PASSED
		//exactly when empty catches with no word of reason existed and failed once none did
		check(worst.isEmpty(), worst.size() + " empty catch(es) without a word of reason: " + worst
				+ (worst.isEmpty() ? "" : ". Say why nothing needs doing there, or do something"));

		substitutingCatches(root);
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
		//the rule's own known answers, before its count is believed
		String[] notACount = {"\t\tMasterTable.read(ws);", "\t\tin.read();"};
		boolean noneCounted = true;
		for (String statement : notACount) {
			noneCounted &= !dropsAnAnswer(statement);
		}
		check(dropsAnAnswer("\t\tin.read(buf);") && dropsAnAnswer("\t\tsuper.skip(n);")
			&& dropsAnAnswer("\t\topen().read(b, 0, n);") && dropsAnAnswer("\t\tdis.skipBytes(4);")
			&& noneCounted && !dropsAnAnswer("\t\tin.readFully(buf);"),
			"the rule's known answers: a stream's read or skip counts, wherever it is called on; a"
			+ " class's static read, readFully and a one-byte read() do not");
		//A KNOWN ANSWER MUST REACH THE TEST IT NAMES (F248's re-drive): the static read and the
		//one-byte read() were written as assignments, which DROPPED refuses before the receiver and
		//SAFE_CALL tests are asked - since F212 they passed whatever those tests said
		List<String> unreached = new ArrayList<>();
		for (String statement : notACount) {
			if (!DROPPED.matcher(statement).matches()) {
				unreached.add(statement.trim());
			}
		}
		check(unreached.isEmpty(), "and every known answer it does not count is a statement that drops its"
			+ " answer, so the receiver and one-byte tests decide it, not the statement test: " + unreached);
		//AND ONLY A DROPPED ANSWER (F212): the rule counted LINES, so a read whose count is kept or
		//returned, or tested where it is called, counted the same as one thrown away - 35 of 76
		check(!dropsAnAnswer("\t\tint n = in.read(b);") && !dropsAnAnswer("\t\treturn dis.read(b);")
			&& !dropsAnAnswer("\t\tif (in.read(b) < len) {") && !dropsAnAnswer("\t\tlong got = s.skip(n);"),
			"and an answer kept, returned or tested where the call is made is not dropped");
		List<String> where = new ArrayList<>();
		for (File j : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (j.getParentFile() != null && j.getParentFile().getName().equals("tests")) {
				continue;
			}
			//comments blanked, lines kept: a call written in a comment drops nothing
			String body = SourceSeamTest.stripComments(new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8));
			int line = 0;
			for (String text : body.split("\\n")) {
				line++;
				if (dropsAnAnswer(text)) {
					where.add(j.getName() + ":" + line);
				}
			}
		}
		check(where.size() <= UNCHECKED_READ_CEILING, "unchecked read/skip answer(s) in production: "
			+ where.size() + ", against a ceiling of " + UNCHECKED_READ_CEILING
			+ ". Both primitives report how much they MANAGED; a dropped answer is a short read"
			+ " nobody noticed, which on a file-backed stream is a zero tail or a wrong offset."
			+ " This may fall and may not rise: " + first(where, 5));
		check(where.size() == UNCHECKED_READ_CEILING, "and the unchecked-read ceiling is what is measured (measured "
			+ where.size()
			+ ", recorded " + UNCHECKED_READ_CEILING + ")" + (where.size() < UNCHECKED_READ_CEILING
			? " - lower it: a slot of slack is a defect the ratchet will not notice" : ""));
		check(!where.isEmpty(), "and the scan still matches something (" + where.size()
			+ ") - a count that has quietly stopped finding anything asserts nothing");
	}

	/** A call on a stream, or on anything that is not a class name. */
	private static final Pattern STREAM_CALL = Pattern.compile(
		"([A-Za-z_][A-Za-z0-9_]*)?\\s*\\.(?:read|skip|skipBytes)\\s*\\(");
	private static final Pattern SAFE_CALL = Pattern.compile("readFully|skipFully|\\.read\\(\\)");
	/**
	 * A call that IS the statement, so its answer goes nowhere (F212): the line opens with the
	 * receiver - no assignment, return or test in front of it - and ends with the call's semicolon.
	 */
	private static final Pattern DROPPED = Pattern.compile(
		"\\s*[A-Za-z_][\\w.()\\[\\]]*\\.(?:read|skip|skipBytes)\\s*\\(.*\\)\\s*;\\s*");

	/**
	 * Whether one line drops a read or skip answer. A call whose receiver is a CLASS name is a
	 * static parser - {@code MasterTable.read(ws)}, {@code VaultManifest.read(f)} - which answers an
	 * object, not a count of bytes managed, and was counted here only because the text matched:
	 * 24 of the 76 this ceiling first held were such calls.
	 */
	static boolean dropsAnAnswer(String text) {
		if (SAFE_CALL.matcher(text).find() || !DROPPED.matcher(text).matches()) {
			return false;
		}
		java.util.regex.Matcher m = STREAM_CALL.matcher(text);
		while (m.find()) {
			String receiver = m.group(1);
			if (receiver == null || !Character.isUpperCase(receiver.charAt(0))) {
				return true;
			}
		}
		return false;
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
