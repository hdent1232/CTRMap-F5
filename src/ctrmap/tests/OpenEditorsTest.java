package ctrmap.tests;

import ctrmap.humaninterface.OpenEditors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

	// ---- plumbing ----------------------------------------------------------
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
