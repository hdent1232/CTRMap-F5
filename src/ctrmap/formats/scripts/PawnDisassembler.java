package ctrmap.formats.scripts;

import java.util.ArrayList;
import java.util.List;

public class PawnDisassembler {

	public static List<PawnSubroutine> disassembleScript(GFLPawnScript scr) {
		scr.decompressThis();
		List<PawnSubroutine> ret = new ArrayList<>();
		PawnSubroutine entryPoint = new PawnSubroutine(0, scr.instructions, scr); //usually just HALT_P
		entryPoint.parent = scr;
		ret.add(entryPoint);
		//finished the main stuff
		for (int ins = entryPoint.getInstructionCount(); ins < scr.instructions.size();) {
			PawnSubroutine newSub = new PawnSubroutine(ins, scr.instructions, scr);
			newSub.parent = scr;
			ret.add(newSub);
			ins += newSub.getInstructionCount();
		}
		return ret;
	}

	/**
	 * Assembles script text. Never throws on malformed input: whatever parsed
	 * is in the result's subroutines and every line that did not is in its
	 * errors, so the caller decides - the editor's live typing ignores them,
	 * Commit refuses on them.
	 */
	public static PawnAssembly assembleScript(String code) {
		PawnAssembly asm = new PawnAssembly(code);
		int ptr = 0;
		while (asm.hasNextLine()) {
			String line = asm.nextLine();
			if (line.startsWith("sub_")) {
				asm.log.add("Found subroutine " + line + " at pointer 0x" + Integer.toHexString(ptr).toUpperCase());
				PawnSubroutine newSub = PawnSubroutine.fromCode(ptr, asm);
				if (newSub != null) {
					newSub.updateDisassembly();
					asm.subroutines.add(newSub);
					ptr += newSub.getFinalRelativePointer();
				}
			} else if (line.length() > 0) {
				asm.error("\"" + line + "\" is outside any subroutine");
			}
		}
		return asm;
	}

	public static int[] getRawInstructions(List<PawnInstruction> ins) {
		List<int[]> raw = new ArrayList<>();
		int targetLength = 0;
		for (PawnInstruction i : ins) {
			int[] srcRaw = i.getRaw();
			targetLength += srcRaw.length;
			raw.add(srcRaw);
		}
		int[] target = new int[targetLength];
		int off = 0;
		for (int[] a : raw) {
			System.arraycopy(a, 0, target, off, a.length);
			off += a.length;
		}
		return target;
	}

	public static int isSubCorrupted(String code, int start) {
		System.out.println(code.substring(Math.max(0, start - 20), start + 50));
		char chara;
		int idx = start;
		int lines = 0;
		if (idx >= code.length()) {
			return lines;
		}
		while ((chara = code.charAt(idx)) != '{') {
			if (chara == '\n') {
				lines++;
			}
			if (code.length() > idx + 3) {
				if (code.substring(idx, idx + 3).equals("sub")) { //new sub without ending this one
					return lines;
				}
			}
			idx++;
			if (code.length() <= idx) { //end of source
				return lines;
			}
		}
		if (idx >= code.length()) {
			return lines;
		}
		while ((chara = code.charAt(idx)) != '}') {
			if (chara == '\n') {
				lines++;
			}
			if (code.length() > idx + 2) {
				if (code.substring(idx, idx + 2).equals("sub")) { //new sub without ending this one
					return lines;
				}
			}
			idx++;
			if (code.length() <= idx) { //end of source
				return lines;
			}
		}
		return -1;
	}
}
