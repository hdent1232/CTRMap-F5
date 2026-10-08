package ctrmap.tests;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnAssembly;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnInstruction.Commands;
import ctrmap.formats.scripts.PawnPrefixEntry;
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

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		check(ctrmap.scripts.GfHash.table().containsKey("PlayerSetBP") && ctrmap.scripts.GfHash.table().containsKey("PlayerGetBP"),
				"control: the bundled native table names PlayerSetBP and PlayerGetBP");
		theEditorReadsBackANativeCallItShowedByName();
		aLineThatIsNotTakenChangesNothingAndSaysWhy();
		theAssemblerAndTheEditorReadAlike();
		theEditorSaysSo(root);
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
		check(call.setArgsFromString("(PlayerSetBP,4)") && call.argumentCells[0] == 1 && call.argumentCells[1] == 4,
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

	static void theEditorSaysSo(File root) throws Exception {
		System.out.println("--- the editor puts the reason on its status line (F242)");
		String editor = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/ScriptEditor.java")));
		int taken = 0;
		int from = 0;
		while ((from = editor.indexOf("se.sayTaken(i.line, scr.lookupInstructionByPtr(i.pointer).takeArgs(", from)) >= 0) {
			taken++;
			from++;
		}
		check(taken == 2 && !editor.contains(".setArgsFromString("),
				"both of the editor's live edits say whether a line was taken (" + taken + " of 2), none drops the answer");
		String window = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/CtrmapMainframe.java")));
		check(window.contains("mScriptPnl.withStatus()"), "and the window shows the editor with its status line");
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
