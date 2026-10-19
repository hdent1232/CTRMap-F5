package ctrmap.tests;

import ctrmap.humaninterface.OpenEditors;
import ctrmap.humaninterface.ZoneEditors;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Save what the editors are holding, and stop if one refuses" is one list, in
 * one order, and a refusal stops what follows it.
 *
 * <p>WHY THIS SUITE EXISTS. That sentence was a chain of {@code &&} over the
 * main window's statics, written out five times - in the window's close
 * handler, in File &gt; Save, in the Zone tab's clone and add-zones and its
 * zone switch, and in the map painter's apply. The five did not agree: two
 * wrote the text editor, one of those also wrote the collision editor, and the
 * three that run before the zone list is rebuilt wrote neither. Nothing
 * anywhere said what the set was, so "does cloning a zone save my collision
 * work?" could only be answered by reading five lines and hoping they were the
 * five.
 *
 * <p>What that chain got RIGHT is the part worth pinning: {@code &&}
 * short-circuits, so an editor that refuses stops the ones after it AND the
 * caller's reload. An editor that could not save must not be followed by a
 * reload that throws its work away. This suite holds the owner to that.
 *
 * <p>The same suite holds {@link ZoneEditors}, the other half of the pair: one
 * says what "save what is open" means, the other what "show this zone" means,
 * and both replaced a list the Zone tab spelled out by name.
 *
 * <p>ORDER: needs no game, no dump and no display; it builds its own editors.
 *
 * Usage: java ctrmap.tests.OpenEditorsTest
 */
public class OpenEditorsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		everyEditorIsAskedInOrder();
		aRefusalStopsTheOnesAfterIt();
		theQuestionIsPassedThrough();
		anEmptySetIsRefused();
		everyZoneEditorIsToldInOrder();
		everyZoneEditorIsToldWhenNothingIsOpen();
		anEmptyZoneSetIsRefused();
		everyRecordFormIsAskedToCommit();
		aRefusalToCommitStopsTheZoneBeingWritten();
		everyEditorInTheWindowsChainReadsItsQuestion(new File(args.length > 0 ? args[0] : "src"));
		whatTheChainAnswersIsSaid(new File(args.length > 0 ? args[0] : "src"));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ---------------------------------------------------------------- 1. order
	static void everyEditorIsAskedInOrder() {
		System.out.println("--- every editor is asked, in the order the list gives");
		List<String> asked = new ArrayList<>();
		OpenEditors editors = new OpenEditors(Arrays.<OpenEditors.Editable>asList(
				say(asked, "camera", true),
				say(asked, "tilemap", true),
				say(asked, "matrix", true),
				say(asked, "collision", true),
				say(asked, "props", true),
				say(asked, "npcs", true),
				say(asked, "zone", true),
				say(asked, "text", true)));
		check(editors.size() == 8, "the set holds the eight editors it was built with (" + editors.size() + ")");
		check(editors.saveAll(true), "and saving them all succeeds when none refuses");
		check(asked.equals(Arrays.asList("camera", "tilemap", "matrix", "collision", "props", "npcs", "zone", "text")),
				"in the order the list gives, which is the order the chains had: " + asked);
	}

	// ------------------------------------------------------------ 2. the refusal
	static void aRefusalStopsTheOnesAfterIt() {
		System.out.println("--- a refusal stops the editors after it, and the caller");
		List<String> asked = new ArrayList<>();
		OpenEditors editors = new OpenEditors(Arrays.<OpenEditors.Editable>asList(
				say(asked, "camera", true),
				say(asked, "tilemap", false),
				say(asked, "matrix", true),
				say(asked, "zone", true)));
		check(!editors.saveAll(true), "the flush answers false, so the caller does not go on to reload");
		check(asked.equals(Arrays.asList("camera", "tilemap")),
				"and the editors after the refusal were never asked: " + asked);

		//the whole point: what the caller does next
		boolean reloaded = false;
		if (editors.saveAll(true)) {
			reloaded = true;
		}
		check(!reloaded, "a caller that guards its reload on the flush does not reload");
	}

	// ------------------------------------------------------------ 3. the question
	static void theQuestionIsPassedThrough() {
		System.out.println("--- whether an editor may ask the user is the caller's to say");
		List<Boolean> heard = new ArrayList<>();
		OpenEditors editors = new OpenEditors(Arrays.<OpenEditors.Editable>asList(
				ask -> {
					heard.add(ask);
					return true;
				}));
		editors.saveAll(true);
		editors.saveAll(false);
		check(heard.equals(Arrays.asList(true, false)),
				"the flag reaches every editor as it was given - File > Save asks nobody: " + heard);
	}

	/**
	 * Census H.1: every editor in the main window's chain is handed the question and answers it. The collision
	 * editor's entry was {@code ask -> { store(); return true; }}: it wrote without asking and said yes whatever
	 * happened, so No to every question of a zone switch still wrote the collisions - and a lambda in a list is
	 * invisible to a scan of method declarations, which is where the "always true" refusal looked.
	 */
	static void everyEditorInTheWindowsChainReadsItsQuestion(File src) throws Exception {
		System.out.println("--- every editor in the window's save chain is handed the question and answers it (census H.1)");
		List<String> planted = ignoringTheQuestion("asList(ask -> a.store(ask), b::store, ask -> {\n\tc.store();\n\treturn true;\n})");
		check(planted.size() == 1 && planted.get(0).contains("c.store()"),
				"control: an entry that never reads its question is found, and one that forwards it is not: " + planted);
		String main = new String(Files.readAllBytes(new File(src, "ctrmap/CtrmapMainframe.java").toPath()),
				StandardCharsets.UTF_8);
		int at = main.indexOf("private OpenEditors buildOpenEditors()");
		int list = at < 0 ? -1 : main.indexOf("asList(", at);
		check(list > 0, "control: the window builds its chain in buildOpenEditors");
		if (list < 0) {
			return;
		}
		List<String> entries = entries(main.substring(list));
		check(entries.size() >= 8, "control: the chain's entries are read (" + entries.size() + ")");
		List<String> wrong = ignoringTheQuestion(main.substring(list));
		check(wrong.isEmpty(), "every entry reads the question it is handed: " + wrong);
	}

	/**
	 * Census M.101, M.124: what the chain answers is said, and a wait that runs out is a failure. File > Save all
	 * dropped the chain's answer, so Ctrl+S over a file that could not be written answered with nothing; and the
	 * headless progress wait dropped its timeout's answer, so a worker that never finished read as finished.
	 */
	static void whatTheChainAnswersIsSaid(File src) throws Exception {
		System.out.println("--- Save all says when not everything was saved, and a wait that runs out fails (census M.101, M.124)");
		String main = new String(Files.readAllBytes(new File(src, "ctrmap/CtrmapMainframe.java").toPath()), StandardCharsets.UTF_8);
		String save = CollisionReadTest.body(SourceSeamTest.stripComments(main), "private void saveAllAction()");
		check(save != null && save.contains("saved = openEditors.saveAll(false)") && save.contains("if (!saved)")
				&& save.contains("catch (RuntimeException"), "Save all reads the chain's answer, says a false, and says a throw");
		//census L.13: the Quick start guide is looked for beside the installed program, not only in the working folder
		String guide = CollisionReadTest.body(SourceSeamTest.stripComments(main), "private void quickStartAction()");
		check(guide != null && guide.contains("Updater.besideTheProgram(\"QUICKSTART.md\""),
				"the Quick start guide is looked for beside the installed program first");
		String dialog = SourceSeamTest.stripComments(new String(Files.readAllBytes(
				new File(src, "ctrmap/humaninterface/LoadingDialog.java").toPath()), StandardCharsets.UTF_8));
		check(dialog.contains("if (!closed.await(") && !dialog.matches("(?s).*[;{}]\\s*closed\\.await\\(.*"),
				"the headless progress wait reads its timeout's answer and never drops it");
		//census L.55: a zone whose atmosphere could not be read showed as one with no fog, and nothing said so
		String fog = CollisionReadTest.body(SourceSeamTest.stripComments(main), "private void refreshSceneFog()");
		int caught = fog == null ? -1 : fog.indexOf("catch (Exception unread)");
		String handler = caught < 0 ? "" : fog.substring(caught);
		check(fog != null && fog.contains("m3DDebugPanel.setFog(") && caught > 0, "control: the fog refresh is found, with its catch");
		check(handler.contains(".warning(") && handler.contains("Ui.reason(unread)"),
				"a fog that could not be read is said, with its reason, not shown as no fog");
	}

	/** The entries of the {@code asList(...)} {@code text} starts with, split at its top-level commas. */
	static List<String> entries(String text) {
		List<String> out = new ArrayList<>();
		int start = text.indexOf('(') + 1;
		int depth = 0;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '(' || c == '{' || c == '[') {
				depth++;
			} else if (c == ')' || c == '}' || c == ']') {
				if (depth == 0) {
					out.add(text.substring(start, i).trim());
					return out;
				}
				depth--;
			} else if (c == ',' && depth == 0) {
				out.add(text.substring(start, i).trim());
				start = i + 1;
			}
		}
		return out;
	}

	/** The entries of that list that never read the question they are handed; a method reference forwards it. */
	static List<String> ignoringTheQuestion(String text) {
		List<String> out = new ArrayList<>();
		for (String entry : entries(text)) {
			if (entry.matches("[\\w.]+::\\w+")) {
				continue;
			}
			Matcher lambda = Pattern.compile("^(\\w+)\\s*->(.*)$", Pattern.DOTALL).matcher(entry);
			if (!lambda.find() || !Pattern.compile("\\b" + Pattern.quote(lambda.group(1)) + "\\b").matcher(lambda.group(2)).find()) {
				out.add(entry.replaceAll("\\s+", " "));
			}
		}
		return out;
	}

	// -------------------------------------------------------------- 4. the empty
	static void anEmptySetIsRefused() {
		System.out.println("--- a set with no editors in it is refused, not quietly true");
		String said = "(nothing was thrown)";
		try {
			new OpenEditors(new ArrayList<OpenEditors.Editable>());
		} catch (IllegalArgumentException refused) {
			said = String.valueOf(refused.getMessage());
		}
		check(said.contains("cannot be empty"),
				"because an empty set would answer 'everything saved' forever: " + said);
	}

	// ------------------------------------------------------- 5. showing a zone
	/**
	 * "Show this zone" reaches every editor that shows one, in the order the
	 * list gives - and the order is a dependency, not a preference: the matrix
	 * panel is handed the map view's matrix, so the map view has to have
	 * loaded first. That used to be two adjacent lines inside a worker thread.
	 */
	static void everyZoneEditorIsToldInOrder() {
		System.out.println("--- showing a zone reaches every editor that shows one, in order");
		List<String> told = new ArrayList<>();
		ZoneEditors editors = new ZoneEditors(Arrays.<ZoneEditors.ZoneView>asList(
				view(told, "map"), view(told, "matrix panel"), view(told, "camera"),
				view(told, "npcs"), view(told, "warps"), view(told, "triggers"), view(told, "script")));
		check(editors.size() == 7, "the set holds the seven editors that show a zone (" + editors.size() + ")");
		editors.show(null);
		check(told.equals(Arrays.asList("show:map", "show:matrix panel", "show:camera",
				"show:npcs", "show:warps", "show:triggers", "show:script")),
				"the map view is told before the matrix panel, which is handed its matrix: " + told);
	}

	/**
	 * And "show nothing" reaches all seven as well. The Zone tab's unload named
	 * three of them; the other four were an absence rather than a decision, and
	 * an editor left showing a zone that is no longer open is what the save
	 * path then trusts.
	 */
	static void everyZoneEditorIsToldWhenNothingIsOpen() {
		System.out.println("--- and so does showing nothing, to all of them");
		List<String> told = new ArrayList<>();
		ZoneEditors editors = new ZoneEditors(Arrays.<ZoneEditors.ZoneView>asList(
				view(told, "map"), view(told, "npcs"), view(told, "warps")));
		editors.clear();
		check(told.equals(Arrays.asList("clear:map", "clear:npcs", "clear:warps")),
				"every editor is asked, whatever clearing means for each: " + told);
	}

	static void anEmptyZoneSetIsRefused() {
		System.out.println("--- an empty zone-editor set is refused too");
		String said = "(nothing was thrown)";
		try {
			new ZoneEditors(new ArrayList<ZoneEditors.ZoneView>());
		} catch (IllegalArgumentException refused) {
			said = String.valueOf(refused.getMessage());
		}
		check(said.contains("cannot be empty"), "because it would silently show a zone to nobody: " + said);
	}

	/**
	 * Every editor that holds a part-typed record is asked to commit it before
	 * the zone is written, and a refusal stops the ones after it.
	 *
	 * <p>WHY. The zone save named the NPC form and the trigger form by hand and
	 * stopped. The WARP form was not named at all, so warp values a user had
	 * typed and not pressed Save on were dropped with no warning - and the NPC
	 * form's answer was thrown away three lines above another refusal that WAS
	 * honoured, so an NPC pointing at a script the zone does not define showed
	 * "Script not defined" and the zone was written anyway. The user saw a
	 * warning and a saved zone with no way to tell which had won.
	 */
	static void everyRecordFormIsAskedToCommit() {
		System.out.println("--- committing what the editors hold reaches all of them, in order");
		List<String> told = new ArrayList<>();
		ZoneEditors editors = new ZoneEditors(Arrays.<ZoneEditors.ZoneView>asList(
				view(told, "map"), view(told, "npcs"), view(told, "warps"), view(told, "triggers")));
		check(editors.commit(), "the commit succeeds when none of them refuses");
		check(told.equals(Arrays.asList("commit:map", "commit:npcs", "commit:warps", "commit:triggers")),
				"and every one of them was asked, the warp form included: " + told);
	}

	static void aRefusalToCommitStopsTheZoneBeingWritten() {
		System.out.println("--- and a refusal stops the editors after it, and the save");
		List<String> told = new ArrayList<>();
		ZoneEditors editors = new ZoneEditors(Arrays.<ZoneEditors.ZoneView>asList(
				view(told, "map"), view(told, "npcs", "npcs"), view(told, "warps"), view(told, "triggers")));
		check(!editors.commit(), "the commit answers false, so the caller does not write the zone");
		check(told.equals(Arrays.asList("commit:map", "commit:npcs")),
				"and the editors after the refusal were never asked: " + told);
	}

	// ---- plumbing ----------------------------------------------------------
	/** An editor that writes down what it was told about the zone. */
	static ZoneEditors.ZoneView view(List<String> log, String name) {
		return view(log, name, null);
	}

	/** The same, but this one refuses to commit - the NPC form's answer. */
	static ZoneEditors.ZoneView view(List<String> log, String name, String refuses) {
		return new ZoneEditors.ZoneView() {
			@Override
			public void show(ctrmap.formats.zone.Zone zone) {
				log.add("show:" + name);
			}

			@Override
			public void clear() {
				log.add("clear:" + name);
			}

			@Override
			public boolean commit() {
				log.add("commit:" + name);
				return !name.equals(refuses);
			}

			@Override
			public void showEntities(ctrmap.formats.zone.Zone zone) {
				log.add("entities:" + name);
			}
		};
	}

	/** An editor that writes down that it was asked, and answers as told. */
	static OpenEditors.Editable say(List<String> log, String name, boolean answer) {
		return ask -> {
			log.add(name);
			return answer;
		};
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
