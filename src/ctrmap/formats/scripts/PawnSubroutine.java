package ctrmap.formats.scripts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PawnSubroutine {

	public String name;
	public int originalPtr;
	public List<PawnInstruction> instructions = new ArrayList<>();

	public GFLPawnScript parent;

	public PawnSubroutine(int startingInstruction, List<PawnInstruction> source, GFLPawnScript parent) {
		this.parent = parent;
		originalPtr = startingInstruction;
		name = "sub_" + Integer.toHexString(source.get(startingInstruction).pointer).toUpperCase();
		int ins = startingInstruction;
		MainLoop:
		for (; ins < source.size();) {
			PawnInstruction instruction = source.get(ins);
			switch (instruction.getCommand()) {
				case 0x2F:
				case 0x30:
					//return, finalize subroutine
					instructions.add(instruction);
					break MainLoop;
				default:
					instructions.add(instruction);
					ins++;
					break;
			}
		}
	}

	public PawnSubroutine(int ptr) {
		this.name = "sub_" + Integer.toHexString(ptr);
		this.originalPtr = ptr;
	}

	public static PawnSubroutine fromCode(int pointer, PawnAssembly asm) {
		PawnSubroutine ret = new PawnSubroutine(pointer);
		ret.originalPtr = pointer;
		String line = null;
		int ptr = ret.originalPtr;
		while (asm.hasNextLine() && !(line = asm.nextLine()).equals("{")) {
			//await subroutine beginning
			if (line.length() > 0) {
				asm.error("\"" + line + "\" found where the \"{\" opening " + ret.name + " was expected");
			}
		}
		if (!asm.hasNextLine()) {
			asm.error(ret.name + " has no body");
			return null;
		}
		while (asm.hasNextLine() && !(line = asm.nextLine()).equals("}")) {
			if (line.length() == 0) {
				continue;
			}
			PawnInstruction newIns = PawnInstruction.fromString(ptr, line, asm);
			newIns.checkJmpConvertArgs();
			if (newIns.getCommand() == 0x82) {
				newIns = caseTblFromString(newIns.pointer, asm);
			}
			if (newIns.cellValue != -1) {
				ret.instructions.add(newIns);
				if (!newIns.hasCompressedArgument) {
					ptr += newIns.argumentCount * 4;
				}
				ptr += 4;
			}
		}
		if (!line.equals("}")) {
			asm.error(ret.name + " has no closing \"}\"");
		}
		asm.log.add("Done parsing instructions for " + ret.name + ", found " + ret.instructions.size() + " instructions.");
		return ret;
	}

	public static PawnInstruction caseTblFromString(int ptr, PawnAssembly asm) {
		PawnInstruction newIns = new PawnInstruction(ptr, 0x82, "");
		String line = null;
		while (asm.hasNextLine() && !(line = asm.nextLine()).equals("{")) {
			//await casetbl beginning
			if (line.length() > 0) {
				asm.error("\"" + line + "\" found where the \"{\" opening the casetbl was expected");
			}
		}
		//in source order: a HashMap reshuffled every vanilla casetbl on the way back
		Map<Integer, Integer> cases = new LinkedHashMap<>();
		int defaultCaseJmp = 0;
		while (asm.hasNextLine() && !(line = asm.nextLine()).equals("}")) {
			if (line.length() == 0) {
				continue;
			}
			//"VALUE => 0xTARGET" is one arm, "* => 0xTARGET" the default
			String notACase = "\"" + line + "\" is not a case - want \"VALUE => 0xTARGET\" or \"* => 0xTARGET\"";
			int arrow = line.lastIndexOf("=>");
			if (arrow < 0) {
				asm.error(notACase);
				continue;
			}
			String value = line.substring(0, arrow).trim();
			try {
				int target = Integer.parseInt(line.substring(arrow + 2).trim().replaceAll("0x", ""), 16);
				if (value.equals("*")) {
					defaultCaseJmp = target;
				} else {
					cases.put(Integer.parseInt(value), target);
				}
			} catch (NumberFormatException e) {
				asm.error(notACase);
			}
		}
		if (!"}".equals(line)) {
			asm.error("the casetbl at 0x" + Integer.toHexString(ptr).toUpperCase() + " has no closing \"}\"");
		}
		newIns.argumentCount = cases.size() * 2 + 2;
		newIns.argumentCells = new int[newIns.argumentCount];
		newIns.argumentCells[0] = cases.size();
		int idx = 2;
		for (Map.Entry e : cases.entrySet()) {
			newIns.argumentCells[idx + 1] = (Integer) e.getValue() - (ptr + idx * 4) - 4;
			newIns.argumentCells[idx] = (Integer) e.getKey();
			idx += 2;
		}
		newIns.argumentCells[1] = defaultCaseJmp - (ptr + 4);
		return newIns;
	}

	public int getInstructionCount() {
		return instructions.size();
	}

	public int getFinalRelativePointer() {
		int ptr = 0;
		for (int i = 0; i < instructions.size(); i++) {
			ptr += 4;
			PawnInstruction ins = instructions.get(i);
			if (!ins.hasCompressedArgument) {
				ptr += ins.argumentCount * 4;
			}
		}
		return ptr;
	}

	public void updateDisassembly() {
		for (int i = 0; i < instructions.size(); i++) {
			instructions.get(i).updateDisassembly();
		}
	}

	public List<String> getAllInstructionStrings(int indentLevel) {
		List<String> ret = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			StringBuilder sb = new StringBuilder();
			StringBuilder indentator = new StringBuilder();
			if (indentLevel != -1) {
				for (int j = 0; j < indentLevel; j++) {
					indentator.append("\t");
				}
			}
			sb.append(indentator);
			sb.append(instructions.get(i).stringValue.replaceAll("\n", "\n" + indentator.toString()));
			ret.add(sb.toString());
		}
		return ret;
	}
}
