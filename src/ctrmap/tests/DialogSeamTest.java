package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.formats.npcreg.NPCRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;

/**
 * The dialog seam: {@link Ui} is the only place in this program that may open a
 * JOptionPane, and a message that goes through it must actually say something.
 *
 * <p>WHY. A raw {@code JOptionPane.show*} call is invisible to a test and
 * cannot run headless at all - it throws HeadlessException with no display. So
 * a guard that detects a problem and then reports it through one is a silent
 * failure with extra steps, and every branch behind a raw confirm is unreachable
 * from a suite, which is why mutants living there survive by default. Ui was
 * built for that reason, about fifteen sites were moved over, and the other 261
 * were left "until a guard needs them" - which is how a seam quietly stops being
 * a seam. This suite is the guard, so the rule outlives whoever last enforced it
 * by hand.
 *
 * <p>What it refuses:
 * <ul>
 * <li>A raw JOptionPane.show* anywhere outside Ui.java, unless it is in the
 *     allowed list below. Tests are included on purpose: a suite that opens a
 *     modal window blocks the battery until somebody clicks it, and that has
 *     happened here.</li>
 * <li>An allowed entry with no reason written against it, more entries than the
 *     ceiling allows, or more raw calls in a file than that file recorded. The
 *     list may shrink for free and cannot grow quietly - the same ratchet
 *     MutationBaselineTest puts on its exclusions.</li>
 * <li>A switch on a confirm's answer that handles CANCEL_OPTION but not
 *     CLOSED_OPTION. Closing a dialog must never mean consent, and six of these
 *     switches simply ran off the end into the write: pressing X on "keep your
 *     changes?" saved, and a headless caller - which gets CLOSED_OPTION by
 *     definition - wrote the file with nobody there.</li>
 * <li>A report whose whole text is a bare {@code getMessage()}. It is null for
 *     a whole family of exceptions, and a dialog that says "null" tells the user
 *     nothing. The tree's idiom is {@code Ui.reason(ex)}, which names the
 *     exception when it gave no message.</li>
 * </ul>
 *
 * <p>And what it proves by running: Ui cannot be made to say nothing. Handed a
 * null or blank text it says so visibly instead of showing an empty dialog, and
 * instead of dying in {@code text.replace} - the report of a failure failing.
 *
 * <p>This suite never calls {@code Ui.enableDialogs()}. Dialogs stay off under
 * test deliberately; BatteryHygieneTest enforces that only the application
 * names it.
 *
 * Usage: java ctrmap.tests.DialogSeamTest [src-root]   (default "src")
 */
public class DialogSeamTest {

	/**
	 * The raw calls that stay raw, as {file, message-argument source, count,
	 * reason}. Every one has a live Swing component where the text goes: the
	 * seam carries a String on purpose, and recording "javax.swing.JPanel[...]"
	 * would be an assertion about nothing at all.
	 *
	 * <p>The message argument is the key rather than a line number, because it
	 * is the fact that makes the call unmigratable - a new raw dialog with a
	 * string literal in it does not match any entry however it is placed. The
	 * count is a ceiling, so migrating one away costs nothing and adding one
	 * costs a code review.
	 */
	private static final String[][] ALLOWED = {
		{"CtrmapMainframe.java", "form", "9",
			"the tool dialogs (fork, rename, empty, export, import, canvas, resize) put a filled-in form in the dialog"},
		{"CtrmapMainframe.java", "tForm", "1",
			"the OBJ importer's new-material form"},
		{"CtrmapMainframe.java", "new javax.swing.JScrollPane(ta)", "1",
			"the reusable-zones report is a scrollable text area, not a string"},
		{"NPCEditForm.java", "panel", "6",
			"the Add NPC wizards (talking, sign, item giver, battle, Give BP, trainer) are forms"},
		{"NPCEditForm.java", "new JScrollPane(ta)", "1",
			"the dialogue editor is an editable text area the user types into"},
		{"TilePainterForm.java", "panel", "1",
			"the sign-text form"},
		{"ZoneLoadingPanel.java", "form", "2",
			"the clone-zone and add-zones forms"},
		{"UpdateUI.java", "body", "1",
			"the update dialog carries a live \"check on startup\" checkbox"},
	};

	/**
	 * How many entries the allowed list may hold. Mirrors ALLOWED's length;
	 * raising it is the decision this ceiling exists to make visible, exactly
	 * as MutationBaselineTest caps its exclusions at 4. Shrinking it is free
	 * and welcome - each entry removed is a dialog a test can finally answer.
	 */
	private static final int ALLOWED_CEILING = 8;

	private static final Pattern RAW_CALL = Pattern.compile("(javax\\.swing\\.)?JOptionPane\\.show([A-Za-z]*)Dialog\\s*\\(");
	/** {@code case JOptionPane.CANCEL_OPTION:}, however it is qualified. */
	private static final String CASE = "case\\s+(?:javax\\.swing\\.)?JOptionPane\\.";
	private static final Pattern CANCEL_CASE = Pattern.compile(CASE + "CANCEL_OPTION\\s*:");
	/**
	 * The house idiom: the two labels are written next to each other, in either
	 * order, so the arm that means "do nothing" catches both. Adjacency rather
	 * than "somewhere in the same switch" because it is unambiguous to check
	 * and unambiguous to read.
	 */
	private static final Pattern PAIRED_CANCEL = Pattern.compile(
			CASE + "(?:CLOSED_OPTION\\s*:\\s*" + CASE + "CANCEL_OPTION"
			+ "|CANCEL_OPTION\\s*:\\s*" + CASE + "CLOSED_OPTION)\\s*:");
	/** Any call into the seam, so its message argument can be read. */
	private static final Pattern UI_CALL = Pattern.compile(
			"(?<![A-Za-z0-9_.])(ctrmap\\.)?Ui\\.(error|message|confirm|option|input)\\s*\\(");
	/** ...handed nothing but an exception's message, which may be null. */
	private static final Pattern BARE_MESSAGE = Pattern.compile(
			"[A-Za-z0-9_.]*get(Localized)?Message\\(\\)");

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		onlyTheSeamOpensADialog(root);
		theAllowedListCannotGrowQuietly();
		closingADialogIsNeverConsent(root);
		aReportNeverSaysOnlyNull(root);
		theSeamCannotSayNothing();
		closedMeansTheRegistryIsNotWritten();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Rule one: every dialog in this program is opened by Ui, or is listed. */
	static void onlyTheSeamOpensADialog(File root) throws Exception {
		Map<String, Integer> seen = new HashMap<>();
		List<String> violations = new ArrayList<>();
		int scanned = 0;
		for (File f : javaSources(new File(root, "ctrmap"))) {
			//Ui.java IS the implementation, and this suite spells the pattern
			//out, so neither can be scanned by it
			if (f.getName().equals("Ui.java") || f.getName().equals("DialogSeamTest.java")) {
				continue;
			}
			scanned++;
			String src = SourceSeamTest.stripComments(read(f));
			Matcher m = RAW_CALL.matcher(src);
			while (m.find()) {
				int line = lineOf(src, m.start());
				String body = messageArgument(src, m.end() - 1);
				String[] entry = allowedFor(f.getName(), body);
				if (entry == null) {
					violations.add(f.getName() + ":" + line + " raw JOptionPane."
							+ m.group(2) + "Dialog, message <" + body + "> - route it through ctrmap.Ui");
					continue;
				}
				String key = f.getName() + " " + body;
				seen.put(key, seen.getOrDefault(key, 0) + 1);
			}
		}
		for (String[] e : ALLOWED) {
			int count = seen.getOrDefault(e[0] + " " + e[1], 0);
			int ceiling = Integer.parseInt(e[2]);
			if (count > ceiling) {
				violations.add(e[0] + ": " + count + " raw call(s) with message <" + e[1]
						+ ">, more than the " + ceiling + " recorded");
			}
		}
		for (String v : violations) {
			System.out.println("  RAW: " + v);
		}
		check(violations.isEmpty(), scanned + " source(s) scanned, every dialog opened through Ui or listed");
	}

	/** Rule two: the list of exceptions is a ceiling, and each one is argued. */
	static void theAllowedListCannotGrowQuietly() {
		check(ALLOWED.length <= ALLOWED_CEILING, ALLOWED.length + " allowed entry/entries, ceiling "
				+ ALLOWED_CEILING + " - growing the list is a decision a human has to make");
		int total = 0;
		boolean argued = true;
		for (String[] e : ALLOWED) {
			total += Integer.parseInt(e[2]);
			argued &= e[3] != null && e[3].trim().length() > 10;
		}
		check(argued, "every allowed entry says why it cannot go through the seam");
		System.out.println("  note: " + total + " raw call(s) remain, all with a live Swing component"
				+ " where the text goes");
	}

	/**
	 * Rule three: an unanswered question means "do nothing". Ui returns
	 * CLOSED_OPTION with no display and no sink, so a switch that handles
	 * CANCEL but not CLOSED runs off the end of itself and does the thing.
	 */
	static void closingADialogIsNeverConsent(File root) throws Exception {
		List<String> violations = new ArrayList<>();
		int paired = 0;
		for (File f : javaSources(new File(root, "ctrmap"))) {
			if (f.getName().equals("DialogSeamTest.java")) {
				continue;
			}
			String src = SourceSeamTest.stripComments(read(f));
			int pairs = count(PAIRED_CANCEL, src);
			paired += pairs;
			Matcher m = CANCEL_CASE.matcher(src);
			int cancels = 0;
			while (m.find()) {
				cancels++;
			}
			if (cancels > pairs) {
				violations.add(f.getName() + ": " + (cancels - pairs) + " of " + cancels
						+ " CANCEL_OPTION case(s) are not written next to a CLOSED_OPTION case"
						+ " - closing the dialog would fall past the switch into the action");
			}
		}
		for (String v : violations) {
			System.out.println("  CONSENT: " + v);
		}
		check(violations.isEmpty(), paired + " confirm switch(es), every one answering a closed"
				+ " dialog the same as cancel");
	}

	static int count(Pattern p, String src) {
		Matcher m = p.matcher(src);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	/** Rule four: a report handed only getMessage() can end up saying "null". */
	static void aReportNeverSaysOnlyNull(File root) throws Exception {
		List<String> violations = new ArrayList<>();
		int reports = 0;
		for (File f : javaSources(new File(root, "ctrmap"))) {
			if (f.getName().equals("DialogSeamTest.java")) {
				continue;
			}
			String src = SourceSeamTest.stripComments(read(f));
			Matcher m = UI_CALL.matcher(src);
			while (m.find()) {
				reports++;
				String body = messageArgument(src, m.end() - 1);
				if (!BARE_MESSAGE.matcher(body).matches()) {
					continue;
				}
				violations.add(f.getName() + ":" + lineOf(src, m.start()) + " reports only <" + body
						+ ">, which is null for a thrower that named no reason");
			}
		}
		for (String v : violations) {
			System.out.println("  EMPTY: " + v);
		}
		check(violations.isEmpty(), reports + " report(s) through the seam, none of which can come"
				+ " out as a bare \"null\"");
	}

	static int lineOf(String src, int at) {
		int line = 1;
		for (int i = 0; i < at; i++) {
			if (src.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	/**
	 * ...and the floor under all of them: whatever a call site hands it, the
	 * seam says something. All three of Ui's paths are checked through the sink
	 * a recording test sees, which is the one every other guard asserts on.
	 */
	static void theSeamCannotSayNothing() {
		List<String> said = Ui.record();
		try {
			Ui.error(null, null, "Entity data not saved");
			Ui.message(null, "   ", "Pack workspace", JOptionPane.WARNING_MESSAGE);
			Ui.confirm(null, "", "Save changes", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			Ui.option(null, null, "Add new zones", JOptionPane.OK_CANCEL_OPTION,
					JOptionPane.WARNING_MESSAGE, new Object[]{"Continue", "Cancel"}, "Cancel");
			Ui.error(null, "zone 12 was not saved", "Save zone");
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 5, "the seam was reached five times: " + said.size());
		for (int i = 0; i < 4 && i < said.size(); i++) {
			check(said.get(i).contains("(no details"),
					"a report handed nothing still says something: " + said.get(i));
		}
		check(said.size() == 5 && said.get(4).equals("Save zone: zone 12 was not saved"),
				"and a report that DOES say something is passed through untouched: "
				+ (said.size() == 5 ? said.get(4) : said));

		//...and the one way a report quotes an exception cannot come out as "null"
		check("disk full".equals(Ui.reason(new java.io.IOException("disk full"))),
				"Ui.reason quotes a message the exception gave: " + Ui.reason(new java.io.IOException("disk full")));
		check("java.lang.NullPointerException".equals(Ui.reason(new NullPointerException())),
				"Ui.reason names an exception that gave no message: " + Ui.reason(new NullPointerException()));
		check("java.lang.IllegalStateException:   ".equals(Ui.reason(new IllegalStateException("  "))),
				"Ui.reason treats a blank message as no message: " + Ui.reason(new IllegalStateException("  ")));
	}

	/**
	 * The one place this rule can be proved rather than read: NPCRegistry.store
	 * asks before writing, and used to run off the end of its switch when the
	 * answer was "closed". Under a suite, "closed" is the only answer there is.
	 */
	static void closedMeansTheRegistryIsNotWritten() throws Exception {
		File f = Scratch.file("dialogseam_reg");
		byte[] before = new byte[]{(byte) 0xAA, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(before);
		}
		//shorter than one 0x18-byte entry, so the constructor reads none of
		//them and never reaches the workspace
		NPCRegistry reg = new NPCRegistry(f);
		reg.modified = true;
		boolean stored;
		List<String> said = Ui.record();
		try {
			stored = reg.store(true);
		} finally {
			Ui.stopRecording();
		}
		check(!said.isEmpty(), "storing with dialogs asks before it writes: " + said);
		check(!stored, "an unanswered save prompt reports that it did NOT store");
		check(Arrays.equals(before, Files.readAllBytes(f.toPath())),
				"and the file on disk is untouched (" + f.length() + " bytes)");
	}

	// ---- plumbing ----

	/** The second argument of a call, from the source, whitespace flattened. */
	static String messageArgument(String src, int openParen) {
		int depth = 0, start = openParen + 1, arg = 0;
		boolean instr = false, inchar = false;
		for (int i = openParen; i < src.length(); i++) {
			char c = src.charAt(i);
			if (instr) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					instr = false;
				}
				continue;
			}
			if (inchar) {
				if (c == '\\') {
					i++;
				} else if (c == '\'') {
					inchar = false;
				}
				continue;
			}
			if (c == '"') {
				instr = true;
			} else if (c == '\'') {
				inchar = true;
			} else if (c == '(' || c == '[' || c == '{') {
				depth++;
				if (depth == 1) {
					start = i + 1;
				}
			} else if (c == ')' || c == ']' || c == '}') {
				depth--;
				if (depth == 0) {
					return arg == 1 ? flatten(src.substring(start, i)) : "";
				}
			} else if (c == ',' && depth == 1) {
				if (arg == 1) {
					return flatten(src.substring(start, i));
				}
				arg++;
				start = i + 1;
			}
		}
		return "";
	}

	static String flatten(String s) {
		return s.trim().replaceAll("\\s+", " ");
	}

	static String[] allowedFor(String file, String body) {
		for (String[] e : ALLOWED) {
			if (e[0].equals(file) && e[1].equals(body)) {
				return e;
			}
		}
		return null;
	}

	static List<File> javaSources(File dir) {
		List<File> out = new ArrayList<>();
		File[] files = dir.listFiles();
		if (files == null) {
			return out;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				out.addAll(javaSources(f));
			} else if (f.getName().endsWith(".java")) {
				out.add(f);
			}
		}
		return out;
	}

	static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
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
