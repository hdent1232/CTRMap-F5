package ctrmap.tests;

import ctrmap.formats.pokedata.PartyParam;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PartyParamCall;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnInstruction.Commands;
import ctrmap.formats.scripts.PawnPrefixEntry;
import ctrmap.humaninterface.ScriptEditor;
import ctrmap.scripts.GfHash;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTextArea;

/**
 * The script editor names the party parameter a PokePartyGetParam / PokePartySetParam call
 * passes (F052).
 *
 * <p>WHY THIS SUITE EXISTS. PartyParam's selector table was read out of the executable and held
 * to every retail call by PartyParamTest, and nothing in the editor reached it: the only way to
 * learn that selector 21 is the Speed IV was to read the Java source. The editor now names the
 * call under the caret, on a line of its own beneath the text - not in the text, which the
 * assembler reads back and which has no comments.
 *
 * <p>ORDER: needs no dump. The script and its natives table are built here; the native names
 * come from the bundled ORAS table and the selector names from PartyParam. PartyParamTest holds
 * the same decoder to the 173 retail calls when a dump is handed to it.
 *
 * Usage: java ctrmap.tests.PartyParamCallTest
 */
public class PartyParamCallTest {

	static int fails = 0;

	/** The hashes PartyParamTest found the two natives under in the retail zone scripts. */
	static final int H_GET = 0xE5AB2CFA, H_SET = 0x6EFC380E;

	public static void main(String[] args) throws Exception {
		theNativesAreTheOnesRetailCalls();
		GFLPawnScript s = script();
		theDecoderNamesEachCall(s);
		theEditorShowsItUnderTheCaret(script());
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static void theNativesAreTheOnesRetailCalls() {
		System.out.println("--- the two natives are looked up under the hashes retail calls them by");
		check(GfHash.hash(PartyParamCall.GET) == H_GET && GfHash.hash(PartyParamCall.SET) == H_SET,
				"known answer: the name hash of each is the one measured in the retail scripts");
		check(GfHash.hashForName(PartyParamCall.GET) == H_GET && GfHash.hashForName(PartyParamCall.SET) == H_SET,
				"and the bundled table agrees");
	}

	static PawnInstruction ins(Commands c, int arg) {
		PawnInstruction i = new PawnInstruction(0, c.ordinal(), "");
		if (i.argumentCells.length > 0) {
			i.argumentCells[0] = arg;
		}
		return i;
	}

	static PawnInstruction sysreq(int nativeIndex, int argBytes) {
		PawnInstruction i = new PawnInstruction(0, Commands.SYSREQ_N.ordinal(), "");
		i.hasCompressedArgument = false;
		i.argumentCells = new int[]{nativeIndex, argBytes};
		i.argumentCount = 2;
		return i;
	}

	/** Pushes for a three-argument call, argument 3 first as Pawn pushes them; null pushes PRI. */
	static List<PawnInstruction> call(int nativeIndex, Integer index, Integer selector, Integer slot) {
		List<PawnInstruction> out = new ArrayList<>();
		for (Integer v : new Integer[]{index, selector, slot}) {
			out.add(v == null ? ins(Commands.PUSH_PRI, 0) : ins(Commands.PUSH_P_C, v));
		}
		out.add(sysreq(nativeIndex, 12));
		return out;
	}

	/** Where each call's SYSREQ_N lands, by what it is, in the order script() lays them out. */
	static final String[] CALLS = {"iv speed", "ribbon", "computed selector", "unnamed", "outside get",
		"outside set", "stray push", "too few pushes", "other native"};
	static final int[] AT = new int[CALLS.length];

	static GFLPawnScript script() {
		List<PawnInstruction> code = new ArrayList<>();
		code.add(ins(Commands.PROC, 0));
		List<List<PawnInstruction>> calls = new ArrayList<>();
		calls.add(call(0, 0, 21, 0));          //Speed IV, read
		calls.add(call(1, 5, 1007, null));     //ribbon 5, set, slot computed
		calls.add(call(0, 0, null, 2));        //selector computed
		calls.add(call(0, 0, 42, 0));          //in the switch, never identified
		calls.add(call(0, 0, 60, 0));          //past the getter's switch
		calls.add(call(1, 9, 1020, 0));        //past the setter's switch: not a confirmed setter
		List<PawnInstruction> stray = call(0, 0, 21, 1);
		stray.add(0, ins(Commands.PUSH_P_C, 77)); //a push that is not one of its arguments
		calls.add(stray);
		List<PawnInstruction> few = new ArrayList<>();
		few.add(ins(Commands.ZERO_PRI, 0));
		few.add(ins(Commands.PUSH_P_C, 0));
		few.add(sysreq(0, 12));                //three arguments, one push: nothing can say which
		calls.add(few);
		calls.add(call(2, 0, 21, 0));          //the same pushes for PlayerGetBP
		for (int c = 0; c < calls.size(); c++) {
			code.addAll(calls.get(c));
			AT[c] = code.size() - 1;
		}
		code.add(ins(Commands.ZERO_PRI, 0));
		code.add(ins(Commands.RETN, 0));
		GFLPawnScript s = PawnRenumberTest.script(code.toArray(new PawnInstruction[0]));
		s.natives.clear();
		for (String name : new String[]{PartyParamCall.GET, PartyParamCall.SET, "PlayerGetBP"}) {
			s.natives.add(new PawnPrefixEntry(8, PawnPrefixEntry.Type.NATIVE, new int[]{0, GfHash.hashForName(name)}));
		}
		for (PawnInstruction i : s.instructions) {
			i.setParent(s);
		}
		return s;
	}

	static String said(GFLPawnScript s, int index) {
		PartyParamCall c = PartyParamCall.at(s, index);
		return c == null ? "(nothing)" : c.describe();
	}

	static int at(String what) {
		for (int i = 0; i < CALLS.length; i++) {
			if (CALLS[i].equals(what)) {
				return AT[i];
			}
		}
		throw new IllegalArgumentException(what);
	}

	static void theDecoderNamesEachCall(GFLPawnScript s) {
		System.out.println("--- each call is named from the table, or says why it cannot be");
		PartyParam.Param speed = PartyParam.get(21);
		check(speed != null && speed.name.equals("IV_SPEED") && PartyParam.set(1007) != null
				&& PartyParam.get(42) == null && !PartyParam.isGetSelector(60) && !PartyParam.isSafeToWrite(1020),
				"control: the table says what this suite expects of selectors 21, 1007, 42, 60 and 1020");
		int sys = at("iv speed");
		String line = said(s, sys);
		check(line.equals(PartyParamCall.GET + ": party slot 0, selector 21 = IV_SPEED (confirmed, PK6 0x74)"),
				"the getter's call names its selector: " + line);
		check(said(s, sys - 1).equals(line) && said(s, sys - 2).equals(line) && said(s, sys - 3).equals(line),
				"and each of the three pushes feeding it names the same call");
		check(said(s, sys - 4).equals("(nothing)"), "the instruction before them names nothing: " + said(s, sys - 4));
		line = said(s, at("ribbon"));
		check(line.equals(PartyParamCall.SET + ": party slot computed at run time, selector 1007 = RIBBON (confirmed, PK6 0x30), index 5"),
				"the setter's call names its selector, its ribbon and a slot it cannot know: " + line);
		line = said(s, at("computed selector"));
		check(line.contains("selector computed at run time") && !line.contains("="),
				"a selector computed at run time is said to be, not guessed at: " + line);
		line = said(s, at("unnamed"));
		check(line.contains("selector 42") && line.contains("not established"), "one never identified says so: " + line);
		line = said(s, at("outside get"));
		check(line.contains("selector 60") && line.contains("outside") && line.contains(PartyParam.GET_MIN + ".." + PartyParam.GET_MAX),
				"one past the getter's switch says so: " + line);
		line = said(s, at("outside set"));
		check(line.contains("NOT a confirmed setter") && line.contains("outside"),
				"a setter call the executable does not confirm says what it writes into the save: " + line);
		int stray = at("stray push");
		check(said(s, stray - 3).contains("selector 21") && said(s, stray - 4).equals("(nothing)"),
				"a push before a call's arguments is not taken for one of them: " + said(s, stray - 4));
		check(said(s, at("too few pushes")).equals("(nothing)") && said(s, at("too few pushes") - 1).equals("(nothing)"),
				"a call whose arguments were not all pushed in place names nothing rather than mislabelling them");
		check(said(s, at("other native")).equals("(nothing)"), "and another native's call is not one of these");
	}

	static void theEditorShowsItUnderTheCaret(GFLPawnScript s) throws Exception {
		System.out.println("--- the script editor shows the call under its caret, on a line of its own (F052)");
		ScriptEditor ed = new ScriptEditor(new RecordingZoneSaver());
		javax.swing.JComponent box = ed.withStatus();
		JLabel line = (JLabel) ScriptAssemblerGuardTest.field(ed, "partyParam");
		check(javax.swing.SwingUtilities.isDescendingFrom(line, box), "the line is part of what the editor's tab shows");
		ed.loadScript(s);
		ScriptAssemblerGuardTest.flush();
		JTextArea area = (JTextArea) ScriptAssemblerGuardTest.field(ed, "disassemblyArea");
		String text = area.getText();
		int sys = at("iv speed");
		int row = s.instructions.get(sys).line;
		check(row > 0 && area.getText(area.getLineStartOffset(row), area.getLineEndOffset(row) - area.getLineStartOffset(row))
				.contains("SYSREQ_N(" + PartyParamCall.GET), "control: the call's line in the text is the call: row " + row);
		String[] shown = new String[3];
		int[] rows = {row, s.instructions.get(sys - 2).line, s.instructions.get(at("other native")).line};
		for (int k = 0; k < rows.length; k++) {
			final int r = rows[k];
			java.awt.EventQueue.invokeAndWait(() -> {
				try {
					area.setCaretPosition(area.getLineStartOffset(r) + 1);
				} catch (javax.swing.text.BadLocationException ex) {
					throw new IllegalStateException(ex);
				}
			});
			ScriptAssemblerGuardTest.flush();
			shown[k] = ed.partyParamLine();
		}
		check(shown[0].equals(said(s, sys)), "on the call's line it says what the decoder says: " + shown[0]);
		check(shown[1].equals(shown[0]), "on the selector's push, the same: " + shown[1]);
		check(shown[2].trim().isEmpty(), "and on another native's call, nothing: '" + shown[2] + "'");
		check(area.getText().equals(text), "and the script's text is what it was - nothing was written into it");

		ed.unload();
		check(ed.script == null && area.getText().isEmpty() && !area.isEditable(), "a zone that closes takes its script"
				+ " out of the editor: no script, no text, nothing to type into (F222)");
		ed.loadScript(s);
		check(ed.script == s && area.isEditable(), "and the next zone's script is editable again");
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
