package ctrmap.formats.scripts;

import ctrmap.formats.pokedata.PartyParam;
import ctrmap.scripts.GfHash;
import java.util.List;

/**
 * One {@code PokePartyGetParam} / {@code PokePartySetParam} call in a script, read the way the
 * game reads it: which of the two natives, and the arguments pushed for it - the selector above
 * all, named from {@link PartyParam} (F052).
 *
 * <p>WHY THIS EXISTS. The selector table was read out of the executable and held to every retail
 * call, and the only place a user could read it was the Java source: the script editor showed
 * {@code SYSREQ_N(PokePartyGetParam,12)} behind three bare pushes. This names the call beside it
 * without touching the script's text - that text is what the assembler reads back, and it has no
 * comment syntax to carry a note.
 *
 * <p>Not: PartyParam - that is what a selector means, read from the executable; this is one call
 * in one script and the selector it passes.
 */
public final class PartyParamCall {

	/** The getter, by the name the bundled ORAS native table gives it. */
	public static final String GET = "PokePartyGetParam";
	/** The setter, likewise. */
	public static final String SET = "PokePartySetParam";

	/** True for PokePartySetParam. */
	public final boolean isSet;
	/** Where the SYSREQ_N is in the script's instruction list. */
	public final int sysreqAt;
	/**
	 * The arguments, args[0] being argument 1 (the push nearest the call); null where the value
	 * is computed at run time rather than pushed as a constant.
	 */
	public final Integer[] args;

	private PartyParamCall(boolean isSet, int sysreqAt, Integer[] args) {
		this.isSet = isSet;
		this.sysreqAt = sysreqAt;
		this.args = args;
	}

	/**
	 * The arguments of the native call at sysreqAt, argument 1 first: Pawn pushes them in reverse,
	 * so the push nearest the call is argument 1. A constant push gives its value, any other push
	 * null. Null altogether when fewer than nargs pushes stand directly before the call - the
	 * arguments were built some other way and nothing here can say which is which.
	 */
	public static Integer[] argsOf(List<PawnInstruction> flat, int sysreqAt, int nargs) {
		Integer[] argv = new Integer[nargs];
		int got = 0;
		for (int k = sysreqAt - 1; k >= 0 && got < nargs; k--) {
			PawnInstruction p = flat.get(k);
			String op = name(p);
			if (!op.startsWith("PUSH")) {
				break;
			}
			argv[got++] = op.endsWith("_C") && p.argumentCells != null && p.argumentCells.length > 0
					? p.argumentCells[0] : null;
		}
		return got < nargs ? null : argv;
	}

	/**
	 * The party-parameter call the instruction at index belongs to - its SYSREQ_N, or one of the
	 * pushes feeding it - or null when it belongs to none.
	 */
	public static PartyParamCall at(GFLPawnScript script, int index) {
		List<PawnInstruction> flat = script.instructions;
		if (index < 0 || index >= flat.size()) {
			return null;
		}
		int k = index;
		while (k < flat.size() && name(flat.get(k)).startsWith("PUSH")) {
			k++;
		}
		if (k >= flat.size()) {
			return null;
		}
		PartyParamCall call = read(script, k);
		//a push belongs to this call only when it is one of its arguments, not an earlier push
		return call == null || k - index > call.args.length ? null : call;
	}

	/** The call at sysreqAt when it is one of the two party natives with its pushes in place, else null. */
	static PartyParamCall read(GFLPawnScript script, int sysreqAt) {
		PawnInstruction ins = script.instructions.get(sysreqAt);
		if (ins.getCommand() != PawnInstruction.Commands.SYSREQ_N.ordinal()
				|| ins.argumentCells == null || ins.argumentCells.length < 2) {
			return null;
		}
		int nativeIndex = ins.argumentCells[0];
		if (nativeIndex < 0 || nativeIndex >= script.natives.size()) {
			return null;
		}
		int[] data = script.natives.get(nativeIndex).data;
		if (data == null || data.length < 2) {
			return null;
		}
		boolean set;
		if (data[1] == GfHash.hashForName(GET)) {
			set = false;
		} else if (data[1] == GfHash.hashForName(SET)) {
			set = true;
		} else {
			return null;
		}
		Integer[] args = argsOf(script.instructions, sysreqAt, ins.argumentCells[1] / 4);
		return args == null ? null : new PartyParamCall(set, sysreqAt, args);
	}

	/** The selector (argument 2), or null when it is computed at run time. */
	public Integer selector() {
		return args.length > 1 ? args[1] : null;
	}

	/** What the selector means, or null when it is computed or was never established. */
	public PartyParam.Param param() {
		Integer s = selector();
		return s == null ? null : isSet ? PartyParam.set(s) : PartyParam.get(s);
	}

	/**
	 * One line for a person: the native, the party slot, the selector and what it is - or why it
	 * cannot be named - and, for the setter, whether the write is one the executable confirms.
	 */
	public String describe() {
		StringBuilder sb = new StringBuilder(isSet ? SET : GET).append(": party slot ")
				.append(args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "computed at run time");
		Integer s = selector();
		PartyParam.Param p = param();
		if (s == null) {
			sb.append(", selector computed at run time - nothing here can name it");
		} else if (!(isSet ? PartyParam.isSetSelector(s) : PartyParam.isGetSelector(s))) {
			sb.append(", selector ").append(s).append(" - outside the native's switch (")
					.append(isSet ? PartyParam.SET_MIN + ".." + PartyParam.SET_MAX : PartyParam.GET_MIN + ".." + PartyParam.GET_MAX)
					.append(")");
		} else if (p == null) {
			sb.append(", selector ").append(s).append(" - in the switch, its meaning not established");
		} else {
			sb.append(", selector ").append(s).append(" = ").append(p.name).append(" (")
					.append(p.certainty.name().toLowerCase())
					.append(p.pk6Offset >= 0 ? String.format(", PK6 0x%02X", p.pk6Offset) : "").append(")");
			if (args.length > 2 && (p.takesIndex || isSet)) {
				sb.append(p.takesIndex ? ", index " : ", value ").append(args[2] == null ? "computed at run time" : String.valueOf(args[2]));
			}
		}
		if (isSet && s != null && !PartyParam.isSafeToWrite(s)) {
			sb.append(" - NOT a confirmed setter: it writes a byte nobody has identified into the save");
		}
		return sb.toString();
	}

	private static String name(PawnInstruction ins) {
		return PawnInstruction.Commands.values()[ins.getCommand()].name();
	}
}
