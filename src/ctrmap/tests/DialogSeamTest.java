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
 *     definition - wrote the file with nobody there. Nor an answer compared with
 *     CANCEL_OPTION in an if, which is the same fall-through without a switch
 *     (F152: closing the shop editor's merge question overwrote code.ips).</li>
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


	/** Writing the zone and re-showing its script, so both can be read back. */
	static final RecordingZoneSaver SAVER = new RecordingZoneSaver();

	/** Where the user is looking, so a placement can be asserted at all. */
	static final RecordingCentre CENTRE = new RecordingCentre();

	/** The 3D gizmo these forms move, so what they told it can be read back. */
	static final RecordingNavi NAVI = new RecordingNavi();
	/** The redraw the forms here are handed: what frame.repaint() was, but readable. */
	static final Redraws REDRAW = new Redraws();

	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


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
		{"NPCEditForm.java", "body", "1",
			"showForm, the one place the Add NPC wizards and the dialogue editor show their forms;"
			+ " the forms are built by the *Form classes and acted on by the add*/editDialogue methods, without a window"},
		{"PaintForm.java", "panel", "1",
			"the map painter's sign-text form (its Prompts)"},
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
	private static final int ALLOWED_CEILING = 7;

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
		Headless.require();
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		onlyTheSeamOpensADialog(root);
		theAllowedListCannotGrowQuietly();
		aFeatureLivesInItsTabAndNotInAWindow(root);
		closingADialogIsNeverConsent(root);
		aCancelIsNeverTheOnlyNo(root);
		aReportNeverSaysOnlyNull(root);
		theSeamCannotSayNothing();
		theSeamCarriesTheWindowItWasGiven();
		theOneKeepQuestionAnswersClosedAsCancel();
		closedMeansTheRegistryIsNotWritten();
		aFileDialogsAnswerIsNeverDiscarded(root);
		onlyTheSeamShowsAFileChooser(root);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * A file dialog called as a bare statement: its answer - Approve or Cancel - thrown away. A
	 * file chooser is always handed its parent, so a call with no argument at all - the progress
	 * dialog's own {@code showDialog()}, which has no answer to give - is not one. The seam's own
	 * {@code Ui.choose} is one too: once every chooser went through it (F147), a bare call to it was
	 * the same thrown-away Cancel, and the show*Dialog shape alone no longer saw any of them.
	 */
	static final Pattern DISCARDED_FILE_DIALOG = Pattern.compile(
			"(?m)^\\s*(?:(?!JOptionPane\\b)[A-Za-z_][\\w.]*\\.show(?:Open|Save)?Dialog\\([^);][^;]*"
			+ "|(?:ctrmap\\.)?Ui\\.choose\\([^;]*)\\);");

	/**
	 * A file dialog's answer is read, never thrown away (F004).
	 *
	 * <p>Swing does not clear the selection on Cancel. Nine dialogs here called {@code
	 * showOpenDialog} or {@code showSaveDialog} as a statement and then asked for the selected
	 * file, so clicking a file and then Cancel went ahead with that file anyway - opening a map
	 * over the loaded one, replacing every collision mesh with an OBJ, setting the workspace
	 * folder. A dialog's answer is the user saying no; dropping it is not hearing them.
	 */
	static void aFileDialogsAnswerIsNeverDiscarded(File root) throws Exception {
		System.out.println("--- a file dialog's Cancel is heard");
		String sample = "jfc.showOpenDialog(this);\n"
				+ "if (fc.showSaveDialog(dlg) != JFileChooser.APPROVE_OPTION) { return; }\n"
				+ "JOptionPane.showMessageDialog(null, \"x\");\n"
				+ "progress.showDialog();\n"
				+ "\t\tctrmap.Ui.choose(frame, jfc, false);\n"
				+ "\t\tif (ctrmap.Ui.choose(frame, jfc, false) == null) { return null; }\n"
				+ "\t\tFile f = Ui.choose(dlg, fc, true);\n";
		int inSample = 0;
		for (Matcher m = DISCARDED_FILE_DIALOG.matcher(sample); m.find(); ) {
			inSample++;
		}
		check(inSample == 2, "the scan finds a bare call, the seam's included, and not one whose answer is read ("
				+ inSample + " of 2)");
		List<String> found = new ArrayList<>();
		for (File f : javaSources(new File(root, "ctrmap"))) {
			if (f.getName().equals("DialogSeamTest.java")) {
				continue;
			}
			String src = SourceSeamTest.stripComments(read(f));
			for (Matcher m = DISCARDED_FILE_DIALOG.matcher(src); m.find(); ) {
				found.add(f.getName() + ":" + lineOf(src, m.start()));
			}
		}
		check(found.isEmpty(), "no file dialog throws its answer away" + (found.isEmpty() ? "" : ": " + found));
	}

	/** A file chooser shown by hand rather than through {@code Ui.choose}. */
	static final Pattern SHOWS_A_CHOOSER = Pattern.compile("\\.show(?:Open|Save)Dialog\\s*\\(");

	/**
	 * Every file chooser is shown by the seam, which answers the file only on approve and which a
	 * recording can answer (F147). Shown by hand, a chooser's Cancel could be read as the file last
	 * clicked (F004), and every branch behind one was out of a headless suite's reach.
	 */
	static void onlyTheSeamShowsAFileChooser(File root) throws Exception {
		System.out.println("--- every file chooser is shown by the seam, and answers only an approve (F147)");
		check(SHOWS_A_CHOOSER.matcher("if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {").find()
				&& !SHOWS_A_CHOOSER.matcher("if (ctrmap.Ui.choose(this, fc, false) == null) {").find(),
				"control: a chooser shown by hand is seen, and one shown through the seam is not");
		List<String> found = new ArrayList<>();
		int sites = 0;
		for (File f : javaSources(new File(root, "ctrmap"))) {
			if (f.getPath().contains(File.separator + "tests" + File.separator) || f.getName().equals("Ui.java")) {
				continue;
			}
			String src = SourceSeamTest.stripComments(read(f));
			for (Matcher m = SHOWS_A_CHOOSER.matcher(src); m.find(); ) {
				found.add(f.getName() + ":" + lineOf(src, m.start()));
			}
			for (Matcher m = Pattern.compile("\\bUi\\.choose\\s*\\(").matcher(src); m.find(); ) {
				sites++;
			}
		}
		check(found.isEmpty(), "no file chooser is shown outside Ui: " + found);
		check(sites >= 21, "control: the 21 choosers that go through it are seen: " + sites);
		String builder = read(new File(root, "ctrmap/humaninterface/builder/Builder.java"));
		check(builder.contains("saveFileDialog(\"Select external location\")") && builder.contains("saveFileDialog(\"Select export location\")"),
				"and the builder's two save targets are picked with a Save dialog, not an Open one");

		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		File picked = new File("picked.bin").getAbsoluteFile();
		List<String> said = Ui.record(picked);
		File got;
		try {
			got = Ui.choose(null, fc, false);
		} finally {
			Ui.stopRecording();
		}
		check(picked.equals(got) && picked.equals(fc.getSelectedFile()) && said.size() == 1 && said.get(0).startsWith("Open file"),
				"a recording answers a chooser with the file it holds: " + got + " " + said);
		said = Ui.record(Integer.valueOf(JOptionPane.YES_OPTION));
		try {
			got = Ui.choose(null, fc, true);
		} finally {
			Ui.stopRecording();
		}
		check(got == null && said.size() == 1 && said.get(0).startsWith("Save file"),
				"anything but a file is a cancel, though the chooser still holds the last file: " + got);
		check(Ui.choose(null, fc, false) == null, "and with dialogs off and nobody recording, nobody can approve one");

		said = Ui.record(picked);
		try {
			got = ctrmap.humaninterface.CodeBinFile.choose(null, new File("."));
		} finally {
			Ui.stopRecording();
		}
		check(picked.equals(got), "a branch behind a chooser is reached headless now: the code.bin picker answers " + got);
		said = Ui.record();
		try {
			got = ctrmap.humaninterface.CodeBinFile.choose(null, new File("."));
		} finally {
			Ui.stopRecording();
		}
		check(got == null && said.size() == 1, "and a cancelled one answers nothing: " + got);
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

	/**
	 * Every top-level window this application can put on screen, and why each one is
	 * not part of a tab.
	 *
	 * <p>WHY THIS LIST EXISTS. The owner's standing rule for this project is that a
	 * feature lives in the part of the UI it belongs to: it is not dumped in a menu and
	 * it does not get a window of its own. The rule was written down, and then a zone
	 * preview was built twice against it - once as a dialog behind a button on another
	 * bar, once as a bubble floating beside a dropdown - and both times the owner opened
	 * the Zone Loader, saw nothing, and had to say so. A rule that is remembered is not a
	 * rule; it is a thing that happens to hold until it does not.
	 *
	 * <p>So a new window now has to be argued for HERE before it will compile clean,
	 * which is the only kind of rule this project trusts. The list may shrink for free.
	 * Entries marked INHERITED DEBT are windows that came with CTRMap or predate the
	 * rule; they are why the ceiling is not lower, and each one is a tab somebody has
	 * not moved yet.
	 */
	private static final String[][] WINDOWS = {
		{"CtrmapMainframe.java", "1",
			"the application window itself, and the only one this program opens on its own "
			+ "account"},
		{"LoadingDialog.java", "1",
			"the progress window every long job shows; it owns no feature and closes itself"},
		{"SetupWizard.java", "1",
			"a modal wizard that runs before there is a workspace, and so before there is a tab "
			+ "to put it in"},
		{"UpdateUI.java", "1",
			"the update progress window, shown before the editor is usable"},
		{"PokePickers.java", "1",
			"a modal picker: it returns the chosen species to its caller and closes"},
		{"GfEnvPicker.java", "1",
			"a modal picker: it returns the chosen atmosphere block to its caller. The fog "
			+ "editor it is opened from is a pane in the World Editor now"},
		{"BuildingPaletteDialog.java", "1",
			"a modal picker: it returns the chosen building to the paint tool that asked"},
	};

	/** Windows allowed in all. It may fall and it may not rise quietly. */
	private static final int WINDOW_CEILING = 7; //9 -> 7: About is a message over the window and ESPICA a pane in Extras (F243)

	/** new JFrame / new JDialog / new JWindow, and classes that ARE one. */
	private static final Pattern WINDOW_MADE = Pattern.compile(
			"(?:new\\s+|extends\\s+)(?:javax\\.swing\\.)?(?:JFrame|JDialog|JWindow)\\s*[(<{]?");

	/**
	 * Rule five: a feature lives in its tab, and a new window is a decision.
	 *
	 * <p>It reads production sources only. A suite may build a window - several do, to
	 * look at what is in it - and that is not a feature going somewhere the owner cannot
	 * find it.
	 */
	static void aFeatureLivesInItsTabAndNotInAWindow(File root) throws Exception {
		System.out.println("--- every top-level window is argued for, and there are no new ones");
		Map<String, Integer> made = new HashMap<>();
		for (File f : javaSources(new File(root, "ctrmap"))) {
			//BY FOLDER, not by spelling the separator: the first version of this line
			//compared against a forward slash on a machine that uses backslashes, so it
			//scanned the suites too and reported a bench harness as a stray window
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			String src = SourceSeamTest.stripComments(read(f));
			Matcher m = WINDOW_MADE.matcher(src);
			int n = 0;
			while (m.find()) {
				n++;
			}
			if (n > 0) {
				made.put(f.getName(), n);
			}
		}
		List<String> wrong = new ArrayList<>();
		int recorded = 0;
		for (String[] e : WINDOWS) {
			recorded += Integer.parseInt(e[1]);
			if (e[2].trim().length() < 20) {
				wrong.add(e[0] + " is allowed a window with no reason written against it");
			}
			int got = made.containsKey(e[0]) ? made.get(e[0]) : 0;
			if (got > Integer.parseInt(e[1])) {
				wrong.add(e[0] + " now makes " + got + " window(s), more than the " + e[1]
					+ " recorded - say why the new one cannot be part of a tab");
			}
		}
		for (Map.Entry<String, Integer> e : made.entrySet()) {
			boolean known = false;
			for (String[] w : WINDOWS) {
				known |= w[0].equals(e.getKey());
			}
			if (!known) {
				wrong.add(e.getKey() + " opens a top-level window and is not in the list. A feature"
					+ " belongs in the tab it is about - that is a standing rule here, and it was"
					+ " broken twice by a zone preview nobody could find. If this really cannot be"
					+ " part of a tab, say so in WINDOWS and raise WINDOW_CEILING deliberately");
			}
		}
		check(wrong.isEmpty(), "every top-level window in the application is argued for ("
			+ made.size() + " file(s) make one)" + (wrong.isEmpty() ? "" : " - " + wrong));
		check(recorded <= WINDOW_CEILING, recorded + " window(s) recorded, ceiling "
			+ WINDOW_CEILING + " - this may fall and may not rise quietly");
		check(Ratchet.tight(recorded, WINDOW_CEILING), Ratchet.said("window", recorded, WINDOW_CEILING));
	}
	/** Rule two: the list of exceptions is a ceiling, and each one is argued. */
	static void theAllowedListCannotGrowQuietly() {
		check(ALLOWED.length <= ALLOWED_CEILING, ALLOWED.length + " allowed entry/entries, ceiling "
				+ ALLOWED_CEILING + " - growing the list is a decision a human has to make");
		check(Ratchet.tight(ALLOWED.length, ALLOWED_CEILING), Ratchet.said("allowed-dialog", ALLOWED.length,
				ALLOWED_CEILING));
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

	/** A confirm's answer compared with CANCEL_OPTION, either way round, however it is qualified. */
	static final Pattern CANCEL_COMPARED = Pattern.compile(
			"[!=]=\\s*(?:javax\\.swing\\.)?(?:JOptionPane\\.)?CANCEL_OPTION\\b|(?<![A-Za-z0-9_])CANCEL_OPTION\\s*[!=]=");

	/**
	 * Rule three for an if: an answer is never compared with CANCEL_OPTION (F152).
	 *
	 * <p>The switch rule above could not see this. The shop editor asked "merge into the code.ips
	 * there?" and returned only on {@code rsl == JOptionPane.CANCEL_OPTION} - so closing the
	 * dialog, which answers CLOSED_OPTION, fell through to "overwrite with shops only" and deleted
	 * the zone-limit patch the game needs to boot. The safe comparison names the answer that goes
	 * ahead ({@code != OK_OPTION}), so everything else, closed included, does nothing.
	 */
	static void aCancelIsNeverTheOnlyNo(File root) throws Exception {
		System.out.println("--- a confirm's answer is never compared with CANCEL_OPTION alone (F152)");
		check(CANCEL_COMPARED.matcher("if (rsl == JOptionPane.CANCEL_OPTION) {").find()
				&& CANCEL_COMPARED.matcher("if (javax.swing.JOptionPane.CANCEL_OPTION != r) {").find()
				&& !CANCEL_COMPARED.matcher("JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {").find()
				&& !CANCEL_COMPARED.matcher("case JOptionPane.CANCEL_OPTION:").find(),
				"control: a comparison with CANCEL_OPTION is seen, an OK_CANCEL_OPTION argument and a case label are not");
		List<String> found = new ArrayList<>();
		for (File f : javaSources(new File(root, "ctrmap"))) {
			if (f.getName().equals("DialogSeamTest.java")) {
				continue;
			}
			if (CANCEL_COMPARED.matcher(SourceSeamTest.stripComments(read(f))).find()) {
				found.add(f.getName());
			}
		}
		check(found.isEmpty(), "no answer is compared with CANCEL_OPTION, which a closed dialog is not: " + found);
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
	 * Rule six: a dialog opened without naming a window is parented to the one
	 * the application gave the seam.
	 *
	 * <p>Eighty-seven call sites used to name {@code CtrmapMainframe.frame}
	 * themselves, eighteen of them outside the window - each reaching into the
	 * main window's statics for the one window this program has ever had. A
	 * dialog with no parent can open BEHIND the editor, and a modal one that
	 * opens behind is a program that has stopped responding as far as the user
	 * can tell; so "the seam holds the window" is not a convenience, it is what
	 * makes dropping the argument safe, and it is what this asserts.
	 *
	 * <p>{@link Ui#record} keeps what was said and throws the parent away, so
	 * this installs a sink that keeps the parent instead.
	 */
	static void theSeamCarriesTheWindowItWasGiven() {
		final List<java.awt.Component> parents = new ArrayList<>();
		Ui.Sink spy = new Ui.Sink() {
			@Override
			public void message(java.awt.Component parent, String text, String title, int type) {
				parents.add(parent);
			}

			@Override
			public int confirm(java.awt.Component parent, String text, String title, int optionType) {
				parents.add(parent);
				return JOptionPane.CLOSED_OPTION;
			}

			@Override
			public int option(java.awt.Component parent, String text, String title, Object[] options) {
				parents.add(parent);
				return JOptionPane.CLOSED_OPTION;
			}

			@Override
			public Object input(java.awt.Component parent, String text, String title, int type,
					Object[] options, Object initial) {
				parents.add(parent);
				return null;
			}
		};
		javax.swing.JPanel window = new javax.swing.JPanel();
		javax.swing.JPanel own = new javax.swing.JPanel();
		Ui.parent(window);
		Ui.into(spy);
		try {
			Ui.error("a", "t");
			Ui.message("a", "t", JOptionPane.INFORMATION_MESSAGE);
			Ui.confirm("a", "t", JOptionPane.YES_NO_OPTION);
			Ui.confirm("a", "t", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			Ui.option("a", "t", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
					new Object[]{"ok"}, "ok");
			Ui.input("a", "t", JOptionPane.QUESTION_MESSAGE, new Object[]{"one"}, "one");
			check(parents.size() == 6, "all six unparented forms reached the seam (" + parents.size() + ")");
			boolean allOverTheWindow = true;
			for (java.awt.Component p : parents) {
				allOverTheWindow &= p == window;
			}
			check(allOverTheWindow, "and every one of them was parented to the window the seam holds " + parents);
			check(Ui.parent() == window, "which the seam also hands back, for the live-form dialogs");

			//a caller that owns a window of its own still names it
			parents.clear();
			Ui.error(own, "a", "t");
			check(parents.size() == 1 && parents.get(0) == own,
					"a caller naming its own window is parented to THAT one, not the default");
		} finally {
			Ui.stopRecording();
			Ui.parent(null);
		}
		check(Ui.parent() == null,
				"and the seam can be given no window at all, which is what a headless run is");
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
	 * Every "keep the changes?" question in the program is now asked by
	 * ctrmap.Ui.askToKeep, so rule three is proved once, directly: each of
	 * JOptionPane's four answers maps to the right one of SAVE, DISCARD and
	 * CANCEL, a closed dialog and a dialog nobody answers are both CANCEL, and
	 * a caller that wants no dialog gets SAVE without the seam being touched.
	 */
	static void theOneKeepQuestionAnswersClosedAsCancel() {
		List<String> said = Ui.record(JOptionPane.YES_OPTION, JOptionPane.NO_OPTION,
				JOptionPane.CANCEL_OPTION, JOptionPane.CLOSED_OPTION);
		//what No does is handed in with the question and run there (F106): counted, so each answer
		//is seen to run it exactly when it should
		int[] putBack = {0};
		Runnable discard = () -> putBack[0]++;
		try {
			check(ctrmap.Ui.askToKeep(true, "Zone header", discard) == ctrmap.Ui.Keep.SAVE && putBack[0] == 0,
					"askToKeep: yes is SAVE, and nothing is put back");
			check(ctrmap.Ui.askToKeep(true, "Zone header", discard) == ctrmap.Ui.Keep.DISCARD && putBack[0] == 1,
					"askToKeep: no is DISCARD, and the editor is put back, once: " + putBack[0]);
			check(ctrmap.Ui.askToKeep(true, "Zone header", discard) == ctrmap.Ui.Keep.CANCEL && putBack[0] == 1,
					"askToKeep: cancel is CANCEL, with nothing put back");
			check(ctrmap.Ui.askToKeep(true, "Zone header", discard) == ctrmap.Ui.Keep.CANCEL && putBack[0] == 1,
					"askToKeep: a closed dialog is CANCEL, never consent, and puts nothing back");
			check(ctrmap.Ui.askToKeep(true, "Zone header", discard) == ctrmap.Ui.Keep.CANCEL && putBack[0] == 1,
					"askToKeep: an answer that never comes is CANCEL too");
			check(said.size() == 5 && said.get(0).equals("Save changes: Zone header has been modified. Do you want to keep the changes?"),
					"askToKeep names what changed, in the words every store() always used: " + (said.isEmpty() ? said : said.get(0)));
			int asked = said.size();
			check(ctrmap.Ui.askToKeep(false, "Zone header", discard) == ctrmap.Ui.Keep.SAVE && said.size() == asked
					&& putBack[0] == 1, "askToKeep with no dialog wanted is SAVE, nobody is asked and nothing is put back");
		} finally {
			Ui.stopRecording();
		}
	}

	/**
	 * The one place this rule can be proved rather than read: saving the NPC
	 * registry asks before writing, and the registry's own store() used to run
	 * off the end of its switch when the answer was "closed". The question has
	 * since moved out of the format class into the NPC form, which owns the
	 * window: {@link NPCRegistry} now only writes or forgets and says which, and
	 * {@code NPCEditForm.saveRegistry} asks and passes the decision down. So the
	 * form is what is driven here, with the registry handed a scratch game and
	 * no workspace open. Under a suite, "closed" is the only answer there is.
	 */
	static void closedMeansTheRegistryIsNotWritten() throws Exception {
		File f = Scratch.file("dialogseam_reg");
		byte[] before = new byte[]{(byte) 0xAA, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(before);
		}
		//shorter than one 0x18-byte entry, so the constructor reads none of
		//them and never reaches the game it is handed
		NPCRegistry reg = new NPCRegistry(f, new FakeGameFiles());
		reg.modified = true;
		ctrmap.humaninterface.NPCEditForm form = new ctrmap.humaninterface.NPCEditForm(new ctrmap.LoadedZone(), TOOLS, REDRAW, NAVI, CENTRE, SAVER, SAVER, TestWindow.mTileMapPanel(), pane -> { }, UndoHistoryTest.overOneNumber(new int[]{0}));
		form.reg = reg;
		boolean stored;
		List<String> said = Ui.record();
		try {
			stored = form.saveRegistry(true);
		} finally {
			Ui.stopRecording();
		}
		check(!said.isEmpty(), "saving with dialogs asks before it writes: " + said);
		check(!stored, "an unanswered save prompt reports that it did NOT store");
		check(reg.modified, "and the registry still knows it has unsaved changes");
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
