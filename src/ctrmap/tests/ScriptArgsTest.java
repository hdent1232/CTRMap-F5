package ctrmap.tests;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnAssembly;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnInstruction.Commands;
import ctrmap.formats.scripts.PawnPrefixEntry;
import ctrmap.humaninterface.ScriptEditor;
import java.io.File;
import java.util.Arrays;

/**
 * The Script Editor reads back what it shows, and says when it cannot (F242).
 *
 * <p>WHY THIS SUITE EXISTS. The editor shows a native call by the native's name -
 * "SYSREQ_N(PlayerSetBP,4)" - and its live-edit parser read only decimals, so editing the 4
 * did nothing: the typed text stayed on screen, the instruction kept its old cells, a save wrote
 * the old instruction, and the editor's own listener dropped the answer. The assembler had learned
 * names; the editor's parser had not. A line that failed at its second argument had also already
 * changed its first.
 *
 * <p>ORDER: needs no dump. The script and its natives table are built here; the names come from
 * the bundled ORAS native table.
 *
 * Usage: java ctrmap.tests.ScriptArgsTest [src root]
 */
public class ScriptArgsTest {

	static {
		Headless.require();   //the editor is built to read its status line
	}

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		check(ctrmap.scripts.GfHash.table().containsKey("PlayerSetBP") && ctrmap.scripts.GfHash.table().containsKey("PlayerGetBP"),
				"control: the bundled native table names PlayerSetBP and PlayerGetBP");
		theEditorReadsBackANativeCallItShowedByName();
		aLineThatIsNotTakenChangesNothingAndSaysWhy();
		theAssemblerAndTheEditorReadAlike();
		theEditorSaysSo(root);
		aTypedWordIsFourBytesOrRefused(root);
		aRefusedPrefixEntryKeepsWhatWasTyped();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A script calling PlayerGetBP (native 0) and PlayerSetBP (native 1). */
	static GFLPawnScript script() {
		GFLPawnScript s = new GFLPawnScript(HandedGameTest.amx());
		s.decompressThis();
		s.natives.clear();
		for (String name : new String[]{"PlayerGetBP", "PlayerSetBP"}) {
			s.natives.add(new PawnPrefixEntry(8, PawnPrefixEntry.Type.NATIVE,
					new int[]{0, ctrmap.scripts.GfHash.hashForName(name)}));
		}
		return s;
	}

	/** SYSREQ_N(native, argCount) in the script. */
	static PawnInstruction sysreq(GFLPawnScript s, int nativeIndex, int argBytes) {
		PawnInstruction ins = new PawnInstruction(0, Commands.SYSREQ_N.ordinal(), "");
		ins.hasCompressedArgument = false;
		ins.argumentCells = new int[]{nativeIndex, argBytes};
		ins.argumentCount = 2;
		s.instructions.clear();
		s.instructions.add(ins);
		ins.setParent(s);
		ins.updateDisassembly();
		return ins;
	}

	static String argsOf(String line) {
		return line.substring(line.indexOf('('));
	}

	static void theEditorReadsBackANativeCallItShowedByName() {
		System.out.println("--- the editor reads back a native call it showed by name (F242)");
		GFLPawnScript s = script();
		PawnInstruction call = sysreq(s, 1, 4);
		String shown = PawnInstruction.getDisassembly(call);
		check(shown.equals("SYSREQ_N(PlayerSetBP,4)"), "control: the editor shows it by name: " + shown);
		String said = call.takeArgs(argsOf(shown).replace(",4)", ",8)"));
		check(said.isEmpty() && Arrays.equals(Arrays.copyOf(call.argumentCells, 2), new int[]{1, 8}),
				"changing its 4 to 8 is taken - the native stays PlayerSetBP: " + Arrays.toString(call.argumentCells) + " '" + said + "'");
		said = call.takeArgs("(PlayerGetBP,8)");
		check(said.isEmpty() && call.argumentCells[0] == 0, "and naming the other native calls it: " + Arrays.toString(call.argumentCells));
		check(call.takeArgs("(PlayerSetBP,4)").isEmpty() && call.argumentCells[0] == 1 && call.argumentCells[1] == 4,
				"the boolean form answers the same");
	}

	static void aLineThatIsNotTakenChangesNothingAndSaysWhy() {
		System.out.println("--- a line that is not taken changes nothing, and says why (F242)");
		GFLPawnScript s = script();
		PawnInstruction call = sysreq(s, 1, 4);
		String said = call.takeArgs("(NoSuchNative,4)");
		check(said.contains("NoSuchNative") && said.contains("not a native") && call.argumentCells[0] == 1,
				"a name the script calls no native by is refused in words: " + said);
		said = call.takeArgs("(0,abc)");
		check(said.contains("argument 2") && said.contains("abc") && call.argumentCells[0] == 1 && call.argumentCells[1] == 4,
				"a line failing at its second argument leaves the first as it was (it became 0): "
				+ Arrays.toString(call.argumentCells) + " - " + said);
		said = call.takeArgs("(1)");
		check(said.contains("takes 2") && call.argumentCells[1] == 4, "and the wrong count says how many it takes: " + said);
	}

	static void theAssemblerAndTheEditorReadAlike() {
		System.out.println("--- the assembler and the editor read one argument alike");
		GFLPawnScript s = script();
		for (String line : new String[]{"SYSREQ_N(PlayerSetBP,12)", "SYSREQ_N(1,12)", "SYSREQ_N(PlayerGetBP,0)"}) {
			PawnAssembly asm = new PawnAssembly(line, s);
			PawnInstruction assembled = PawnInstruction.fromString(0, line, asm);
			PawnInstruction edited = sysreq(s, 0, 0);
			String said = edited.takeArgs(argsOf(line));
			check(said.isEmpty() && Arrays.equals(Arrays.copyOf(assembled.argumentCells, 2), Arrays.copyOf(edited.argumentCells, 2)),
					line + ": the assembler and the editor make the same cells " + Arrays.toString(Arrays.copyOf(edited.argumentCells, 2)));
		}
	}

	/**
	 * Census H.36, H.37: a typed word is four bytes or it is refused, saying which line. Data mode parsed each line
	 * as hex with no check - a blank or mistyped line threw out of the Commit button's listener where nobody saw
	 * it, and a short one silently became a different word - and a prefix entry wrote its first field before the
	 * second could fail.
	 */
	static void aTypedWordIsFourBytesOrRefused(File root) throws Exception {
		System.out.println("--- a typed word is four bytes or it is refused, saying which line (census H.36, H.37)");
		check(ScriptEditor.typedWord("01 02 03 04") == 0x01020304 && ScriptEditor.typedWord("0102 0304") == 0x01020304
				&& ScriptEditor.typedWord("FF FF FF FF") == -1, "control: four bytes read in the order typed, spaces anywhere");
		for (String bad : new String[]{"01 02 03", "01 02 03 04 05", "zz zz zz zz", ""}) {
			String said = refusal(() -> ScriptEditor.typedWord(bad));
			check(said != null && said.contains("is not a word"), "\"" + bad + "\" is refused in words: " + said);
		}
		check(ScriptEditor.getDataInstructions("01 02 03 04\n05 06 07 08\n").size() == 2,
				"two typed lines are two data words, the text's last line end no third");
		String shortLine = refusal(() -> ScriptEditor.getDataInstructions("01 02 03 04\n05 06 07\n"));
		check(shortLine != null && shortLine.startsWith("line 2:"), "a short line is refused by its number, not taken as"
				+ " another word: " + shortLine);
		String blank = refusal(() -> ScriptEditor.getDataInstructions("01 02 03 04\n\n05 06 07 08"));
		check(blank != null && blank.startsWith("line 2:"), "and so is a blank one between words: " + blank);
		String editor = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/ScriptEditor.java")));
		check(!editor.contains("parseUnsignedInt(") && !editor.contains("Integer.parseInt("),
				"the editor reads every typed word through typedWord and no other parse");
		String save = CollisionReadTest.body(editor, "public boolean savePrefixEntry()");
		int read = save == null ? -1 : save.indexOf("typedWord(name.getText())");
		int written = save == null ? -1 : save.indexOf("currentEntry.data[0] =");
		check(read > 0 && written > read, "a prefix entry reads both fields before it writes either (" + read + ", " + written + ")");
		//census M.78: New prefix entry added to the open script's list before any script was open, and threw
		String add = CollisionReadTest.body(editor, "private void btnNewPrefixEntryActionPerformed(");
		int asked = add == null ? -1 : add.indexOf("if (currentEntries == null)");
		int added = add == null ? -1 : add.indexOf("currentEntries.add(");
		check(asked > 0 && added > asked, "New prefix entry with no script open adds nothing (" + asked + ", " + added + ")");
		//census M.12: the gutter of addresses is set on the switch back to code as well as on the switch to data
		String swap = CollisionReadTest.body(editor, "private void btnIsDataEditActionPerformed(");
		check(swap != null && swap.contains("ptrs.setText(getDataPtr(") && swap.contains("ptrs.setText(getPtrText("),
				"switching between data and code sets the gutter of addresses both ways");
	}

	private interface Act {
		Object run();
	}

	/** What an act threw as IllegalArgumentException, or null when it did not throw. */
	private static String refusal(Act act) {
		try {
			act.run();
			return null;
		} catch (IllegalArgumentException refused) {
			return refused.getMessage();
		}
	}

	static void theEditorSaysSo(File root) throws Exception {
		System.out.println("--- the editor puts the reason on its status line (F242)");
		String editor = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/ScriptEditor.java")));
		int taken = 0;
		int from = 0;
		while ((from = editor.indexOf("se.sayTaken(i.line, scr.lookupInstructionByPtr(i.pointer).takeArgs(", from)) >= 0) {
			taken++;
			from++;
		}
		//NONE DROPS THE ANSWER: every takeArgs call in the editor is one of those handed to sayTaken
		int calls = editor.split("\\.takeArgs\\(", -1).length - 1;
		check(taken == 2 && calls == taken,
				"both of the editor's live edits say whether a line was taken (" + taken + " of 2), and no other call drops"
				+ " the answer (" + calls + " takeArgs call(s) in all)");
		String window = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/CtrmapMainframe.java")));
		check(window.contains("mScriptPnl.withStatus()"), "and the window shows the editor with its status line");
		//and the status line itself, on the editor (the 2026-10-15 shared sweep, ScriptEditor 109, drop-report,
		//SURVIVED: the line was read in the source only)
		ScriptEditor se = new ScriptEditor(new RecordingZoneSaver());
		java.lang.reflect.Field statusField = ScriptEditor.class.getDeclaredField("status");
		statusField.setAccessible(true);
		javax.swing.JLabel status = (javax.swing.JLabel) statusField.get(se);
		boolean notTaken = se.sayTaken(4, "it is not a number");
		String notTakenSays = status.getText();
		boolean wasTaken = se.sayTaken(4, "");
		check(!notTaken && notTakenSays.equals("Line 5 not taken: it is not a number - the instruction is as it was.")
				&& wasTaken && status.getText().equals(" "),
				"a line not taken is said on the status line, by number and why, and one taken clears it: \"" + notTakenSays
				+ "\", then \"" + status.getText() + "\"");
	}

	/**
	 * A prefix entry refused for a field that is not a word answers that it was not saved, and its Save button keeps
	 * what was typed (the 2026-10-15 shared sweep, ScriptEditor 840, flip-return, SURVIVED: no caller read the answer,
	 * and Save showed the entry again as stored, over the text the refusal had just named). On the editor, over the made
	 * script's natives.
	 */
	static void aRefusedPrefixEntryKeepsWhatWasTyped() throws Exception {
		System.out.println("--- a prefix entry refused for a typed field says so, and keeps what was typed");
		ScriptEditor se = new ScriptEditor(new RecordingZoneSaver());
		//the script handed to the prefix list alone: loadScript also lays out its code, and the made script's stub code
		//holds a branch the editor refuses to renumber
		java.lang.reflect.Field s = ScriptEditor.class.getDeclaredField("script");
		s.setAccessible(true);
		s.set(se, script());
		java.lang.reflect.Field cat = ScriptEditor.class.getDeclaredField("prefixCat");
		cat.setAccessible(true);
		((javax.swing.JComboBox<?>) cat.get(se)).setSelectedIndex(1);   //Natives
		//selected in the list as the user selects it - Save shows the list's selection again - and shown
		java.lang.reflect.Field list = ScriptEditor.class.getDeclaredField("prefixItemList");
		list.setAccessible(true);
		((javax.swing.JList<?>) list.get(se)).setSelectedIndex(0);
		se.showPrefixEntry(0);
		java.lang.reflect.Field a = ScriptEditor.class.getDeclaredField("address");
		a.setAccessible(true);
		javax.swing.JTextField address = (javax.swing.JTextField) a.get(se);
		java.lang.reflect.Field b = ScriptEditor.class.getDeclaredField("btnSavePrefixEntry");
		b.setAccessible(true);
		check(address.getText().length() > 0, "control: native 0 is shown, its address in the field (" + address.getText() + ")");
		address.setText("zz zz zz zz");
		java.util.List<String> said = ctrmap.Ui.record();
		boolean saved;
		String kept;
		try {
			saved = se.savePrefixEntry();
			((javax.swing.AbstractButton) b.get(se)).doClick();
			kept = address.getText();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!saved && "zz zz zz zz".equals(kept) && said.size() == 2 && said.get(0).contains("Prefix entry 0 was not saved"),
				"refused, it answers not saved, and Save leaves the typed text to correct: " + saved + ", \"" + kept + "\", "
				+ said);
		//and a word typed is saved, and answered so (the 2026-10-15 shared sweep, ScriptEditor 858, flip-return,
		//SURVIVED) - flipped, every save that went through answered that it had not
		address.setText("01 02 03 04");
		said = ctrmap.Ui.record();
		try {
			saved = se.savePrefixEntry();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		int stored = ((ctrmap.formats.scripts.GFLPawnScript) s.get(se)).natives.get(0).data[0];
		check(saved && said.isEmpty() && stored == Integer.reverseBytes(0x01020304),
				"a word typed is saved into the native, and answered saved: " + saved + ", 0x" + Integer.toHexString(stored)
				+ ", " + said);
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
