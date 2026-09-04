package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnAssembly;
import ctrmap.formats.scripts.PawnDisassembler;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnSubroutine;
import ctrmap.humaninterface.ScriptEditor;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JTextArea;

/**
 * Guards the script assembler against the two silent failures the script
 * editor shipped with, both of which reported success:
 * <ol>
 * <li>A mistyped mnemonic - PSUH_C for PUSH_C - was dropped from the assembled
 *     script with nothing to show for it but a smaller "found N valid
 *     instructions". Commit wrote the shortened script in the same click, so
 *     every instruction after the typo moved 8 bytes and every jump across the
 *     gap landed early; and because the editor rewrites its text from the
 *     instructions, the typo itself vanished, leaving nothing to point at when
 *     the map hardlocked. Now every line that does not parse is an entry in
 *     {@link PawnAssembly#errors} naming the line and the token, and the
 *     editor refuses to commit while there is one.</li>
 * <li>A casetbl with its closing brace deleted threw NoSuchElementException
 *     out of the assembler. The editor had pointed System.out and System.err
 *     at its output box around that call with no finally, so from then on
 *     every diagnostic the rest of the application printed - GARC pack
 *     failures, the WorkspaceIntegrity report - went into a stale text area.
 *     The assembler now reports malformed text instead of throwing, and
 *     nothing in the source redirects the JVM's streams (SourceSeamTest).</li>
 * </ol>
 * The corpus half assembles the disassembly of every vanilla zone script and
 * requires zero errors and identical instructions, so the strictness can never
 * lock a real script out of Commit.
 *
 * <p>The editor half presses the editor's own buttons without a window:
 * Commit with a typo is refused where the user can see it and leaves the
 * script alone, Commit of a clean script says nothing and keeps every
 * instruction, Run assembler reports either way, and Commit in data mode
 * writes data words rather than running the assembler over them. Mutation
 * testing had turned every one of those branches around with the battery
 * green, because no suite had ever pressed the buttons. The lines that wait
 * for an opening brace are held the same way: a blank line before it is
 * nothing, a stray token is an error that names it.
 *
 * Usage: java ctrmap.tests.ScriptAssemblerGuardTest &lt;path-to-zonedata-a013-garc&gt;
 */
public class ScriptAssemblerGuardTest {

	static int fails = 0;

	static final String CLEAN = "sub_0\n{\n\tPUSH_C(1)\n\tPUSH_C(2)\n\tPUSH_C(3)\n\tRETN(0)\n}\n";

	public static void main(String[] args) throws Exception {
		String garcPath = args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/1/3";
		cleanScript();
		mistypedMnemonic();
		badArgument();
		strayLine();
		linesBeforeTheBrace();
		truncatedCasetbl();
		corpusRoundTrip(new File(garcPath));
		editorButtons(new File(garcPath));
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The baseline: a well-formed script assembles without a word of complaint. */
	static void cleanScript() {
		PawnAssembly asm = PawnDisassembler.assembleScript(CLEAN);
		check(asm.errors.isEmpty(), "a clean script has no errors " + asm.errors);
		check(asm.getInstructionCount() == 4, "a clean script keeps all 4 instructions, got " + asm.getInstructionCount());
		int pushC = PawnInstruction.Commands.PUSH_C.ordinal();
		int retn = PawnInstruction.Commands.RETN.ordinal();
		int[] raw = PawnDisassembler.getRawInstructions(instructions(asm));
		check(Arrays.equals(raw, new int[]{pushC, 1, pushC, 2, pushC, 3, retn}),
				"a clean script assembles to the expected cells, got " + Arrays.toString(raw));
	}

	/** The live defect: one mistyped mnemonic. */
	static void mistypedMnemonic() {
		PawnAssembly asm = PawnDisassembler.assembleScript(CLEAN.replace("PUSH_C(2)", "PSUH_C(2)"));
		check(asm.errors.size() == 1, "a mistyped mnemonic is exactly one error, got " + asm.errors);
		String error = asm.errors.isEmpty() ? "" : asm.errors.get(0);
		check(error.startsWith("line 4:"), "the error names the line: " + error);
		check(error.contains("\"PSUH_C\""), "the error names the token: " + error);
		check(error.contains("not an instruction"), "the error says what is wrong: " + error);
		check(asm.report().contains(error), "the report the editor shows carries the error");
	}

	/** An argument the assembler cannot read must not silently become 0. */
	static void badArgument() {
		PawnAssembly asm = PawnDisassembler.assembleScript(CLEAN.replace("PUSH_C(3)", "PUSH_C(0x3)"));
		check(asm.errors.size() == 1, "an unreadable argument is exactly one error, got " + asm.errors);
		String error = asm.errors.isEmpty() ? "" : asm.errors.get(0);
		check(error.startsWith("line 5:") && error.contains("\"0x3\""), "the error names the line and the argument: " + error);
	}

	/** Text outside every subroutine used to be skipped without a word. */
	static void strayLine() {
		PawnAssembly asm = PawnDisassembler.assembleScript(CLEAN + "PUSH_C(4)\n");
		check(asm.errors.size() == 1 && asm.errors.get(0).startsWith("line 8:"),
				"a line outside any subroutine is an error naming the line, got " + asm.errors);
	}

	/** Between a name and its brace: a blank line is nothing, anything else is named. */
	static void linesBeforeTheBrace() {
		PawnAssembly asm = PawnDisassembler.assembleScript("sub_0\n\n{\n\tPUSH_C(1)\n\tRETN(0)\n}\n");
		check(asm.errors.isEmpty() && asm.getInstructionCount() == 2,
				"a blank line between a subroutine's name and its brace is nothing: " + asm.errors);
		asm = PawnDisassembler.assembleScript("sub_0\nSTRAY\n{\n\tPUSH_C(1)\n\tRETN(0)\n}\n");
		check(asm.errors.size() == 1 && asm.errors.get(0).startsWith("line 2:") && asm.errors.get(0).contains("\"STRAY\""),
				"a stray token there is an error naming the line and the token: " + asm.errors);
		String table = "sub_0\n{\n\tCASETBL\n%s\t{\n\t\t1 => 0x20\n\t\t* => 0x30\n\t}\n\tRETN(0)\n}\n";
		asm = PawnDisassembler.assembleScript(String.format(table, "\n"));
		check(asm.errors.isEmpty(), "a blank line between CASETBL and its brace is nothing: " + asm.errors);
		asm = PawnDisassembler.assembleScript(String.format(table, "\tSTRAY\n"));
		check(asm.errors.size() == 1 && asm.errors.get(0).contains("\"STRAY\"") && asm.errors.get(0).contains("casetbl"),
				"a stray token before the casetbl's brace is an error naming it: " + asm.errors);
	}

	/** The casetbl that used to throw out of the assembler, and with it out of the editor's stream swap. */
	static void truncatedCasetbl() {
		PawnAssembly asm;
		try {
			asm = PawnDisassembler.assembleScript("sub_0\n{\n\tCASETBL\n\t{\n\t\t1 => 0x20\n\t\t2 => 0x30\n");
		} catch (RuntimeException ex) {
			System.out.println("  FAIL: a truncated casetbl threw " + ex + " instead of reporting");
			fails++;
			return;
		}
		boolean named = false;
		for (String error : asm.errors) {
			named |= error.contains("casetbl") && error.contains("}");
		}
		check(named, "a truncated casetbl is reported, naming the missing brace: " + asm.errors);
	}

	/**
	 * Every vanilla zone script's disassembly must assemble back to the same
	 * instructions with no errors - the strictness above must never refuse a
	 * real script.
	 */
	static void corpusRoundTrip(File garc) throws Exception {
		if (!garc.isFile()) {
			System.out.println("  skip: no ZoneData GARC at " + garc);
			return;
		}
		GARC zo = new GARC(garc);
		int scripts = 0, bad = 0;
		for (int z = 0; z < zo.length - 2; z++) {
			byte[] sub = SysreqNameTest.sub(zo.getDecompressedEntry(z), 2);
			if (sub == null || sub.length < 8) {
				continue;
			}
			GFLPawnScript s;
			try {
				s = new GFLPawnScript(sub);
				s.decompressThis();
			} catch (Exception ex) {
				continue;
			}
			scripts++;
			PawnInstruction.nativeResolver = s;
			PawnAssembly asm = PawnDisassembler.assembleScript(ScriptEditor.getDisassemblyTextForArea(s));
			int[] want = PawnDisassembler.getRawInstructions(s.instructions);
			int[] got = PawnDisassembler.getRawInstructions(instructions(asm));
			if (!asm.errors.isEmpty() || !Arrays.equals(want, got)) {
				if (bad++ < 5) {
					System.out.println("  zone " + z + ": " + asm.errors
							+ (Arrays.equals(want, got) ? "" : " (cells differ: " + want.length + " vs " + got.length + ")"));
				}
			}
		}
		PawnInstruction.nativeResolver = null;
		check(scripts > 400, "the corpus has zone scripts: " + scripts);
		check(bad == 0, "every vanilla script's disassembly assembles back to itself (" + bad + " of " + scripts + " did not)");
	}

	/**
	 * The editor's buttons, pressed on a real zone script with no window. The
	 * zone panel has no zone open, so a committed script has nowhere to go
	 * and the commit's store is a no-op that succeeds.
	 */
	static void editorButtons(File garc) throws Exception {
		if (!garc.isFile()) {
			System.out.println("  skip: no ZoneData GARC at " + garc);
			return;
		}
		GARC zo = new GARC(garc);
		GFLPawnScript s = null;
		String text = null;
		for (int z = 0; z < zo.length - 2 && s == null; z++) {
			byte[] sub = SysreqNameTest.sub(zo.getDecompressedEntry(z), 2);
			if (sub == null || sub.length < 8) {
				continue;
			}
			try {
				GFLPawnScript cand = new GFLPawnScript(sub);
				cand.decompressThis();
				PawnInstruction.nativeResolver = cand;
				String t = ScriptEditor.getDisassemblyTextForArea(cand);
				if (t.contains("PUSH_C(") && PawnDisassembler.assembleScript(t).errors.isEmpty()) {
					s = cand;
					text = t;
				}
			} catch (Exception ex) {
			}
		}
		if (s == null) {
			System.out.println("  skip: no zone script with a PUSH_C to mistype");
			return;
		}
		CtrmapMainframe.mZonePnl = new ZoneLoadingPanel();
		ScriptEditor ed = new ScriptEditor();
		ed.loadScript(s);
		flush();
		int[] before = PawnDisassembler.getRawInstructions(s.instructions);
		JTextArea area = (JTextArea) field(ed, "disassemblyArea");
		JTextArea output = (JTextArea) field(ed, "assemblerOutput");
		AbstractButton commit = (AbstractButton) field(ed, "btnSave");
		AbstractButton run = (AbstractButton) field(ed, "btnTestAssembly");
		//a button that throws is a button that failed; without a window the
		//throw has nowhere to go but here
		try {
			//Commit with one mistyped mnemonic
			type(ed, area, text.replaceFirst("PUSH_C\\(", "PSUH_C("));
			List<String> said = ctrmap.Ui.record();
			try {
				commit.doClick();
				flush();
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(said.size() == 1 && said.get(0).contains("did not assemble"), "Commit with a mistyped mnemonic is refused where the user can see it: " + said);
			check(Arrays.equals(before, PawnDisassembler.getRawInstructions(s.instructions)), "and the script is untouched");
			run.doClick();
			flush();
			check(output.getText().startsWith("Assembler refused") && Arrays.equals(before, PawnDisassembler.getRawInstructions(s.instructions)),
					"Run assembler with the typo reports the refusal and leaves the script alone: " + firstLine(output));

			//Commit the clean text
			type(ed, area, text);
			said = ctrmap.Ui.record();
			try {
				commit.doClick();
				flush();
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(said.isEmpty() && Arrays.equals(before, PawnDisassembler.getRawInstructions(s.instructions)),
					"Commit of a clean script says nothing and keeps every instruction: " + said);
			run.doClick();
			flush();
			check(output.getText().contains("Assembled " + s.instructions.size() + " instructions"),
					"Run assembler on a clean script reports what it assembled: " + firstLine(output));

			//Commit in data mode writes data words, not the assembler's verdict
			int words = s.data.size();
			((AbstractButton) field(ed, "btnIsDataEdit")).doClick();
			said = ctrmap.Ui.record();
			try {
				commit.doClick();
				flush();
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(said.isEmpty() && s.data.size() == words, "Commit in data mode writes the " + words + " data word(s) with nothing to say: " + said);
		} catch (RuntimeException ex) {
			check(false, "an editor button threw " + ex);
		}
	}

	/** Replaces the editor's text the way a paste does, without the live per-keystroke hooks. */
	static void type(ScriptEditor ed, JTextArea area, String text) throws Exception {
		//Drain the EDT first. ScriptEditor.updateDocument posts
		//disassemblyArea.setText through invokeLater, so text typed while that
		//is still queued gets overwritten by it - the editor then assembles the
		//OLD text, the expected error never appears, and two checks fail. This
		//suite lost that race about once in fifty runs and lost it in the
		//battery, which is worse than a bug: a guard nobody can trust gets
		//switched off.
		flush();
		ed.loaded = false;
		area.setText(text);
		ed.loaded = true;
		flush();
	}

	/** Waits for everything already queued on the event thread to finish. */
	static void flush() throws Exception {
		java.awt.EventQueue.invokeAndWait(() -> {
		});
	}

	static String firstLine(JTextArea area) {
		String t = area.getText();
		int nl = t.indexOf('\n');
		return nl < 0 ? t : t.substring(0, nl);
	}

	static Object field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static List<PawnInstruction> instructions(PawnAssembly asm) {
		List<PawnInstruction> all = new ArrayList<>();
		for (PawnSubroutine sub : asm.subroutines) {
			all.addAll(sub.instructions);
		}
		return all;
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
