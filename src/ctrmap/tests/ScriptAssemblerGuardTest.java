package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnAssembly;
import ctrmap.formats.scripts.PawnDisassembler;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnSubroutine;
import ctrmap.humaninterface.ScriptEditor;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
		truncatedCasetbl();
		corpusRoundTrip(new File(garcPath));
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
