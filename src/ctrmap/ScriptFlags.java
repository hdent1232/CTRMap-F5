package ctrmap;

import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.gamedef.ArchiveType;
import ctrmap.scripts.GfHash;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Which event flags a game's zone scripts name - what a one-time giver's flag is held to (F085).
 *
 * <p>MEASURED 2026-10-09 over the 536 retail ORAS zone scripts: 1,383 flag calls (FlagGet 370, FlagSet
 * 677, FlagReset 336) name 621 distinct flags as the constant pushed before the call, from 10 to 3,080.
 * 72 more calls take a flag computed at run time, which no reading of constants can see, and the
 * executable keeps flags of its own that no script names at all. So this answers what the SCRIPTS
 * name - read as the game holds them, its edits included, so a flag an earlier one-time giver took is
 * named too - and a flag it does not refuse is free of scripts, not proven free in game.
 *
 * <p>A flag past the highest one a script names is refused as well: the save holds every flag up to
 * that one, since a script uses it, and nothing here can say how far past it the save goes.
 *
 * <p>Not: {@link ZoneTables} - that reads the zone tables' rows; this reads the zones' scripts.
 */
public final class ScriptFlags {

	/** The flag natives a constant is pushed for, by the names the bundled native table gives. */
	private static final String[] FLAG_NATIVES = {"FlagGet", "FlagSet", "FlagSetReq", "FlagReset"};

	/**
	 * Every flag pushed as a constant before a flag native, in any zone script. A flag call whose flag is
	 * computed names none here - 72 of them in retail - which is why a flag not named is "used by no
	 * script that names it", and the form says one-time givers are unproven in game.
	 */
	public final SortedSet<Integer> named;

	private ScriptFlags(SortedSet<Integer> named) {
		this.named = Collections.unmodifiableSortedSet(named);
	}

	/**
	 * Reads every zone's script as {@code game} holds it - a staged edit where there is one, the
	 * archive's entry otherwise, nothing extracted.
	 *
	 * @throws IOException naming the zone, when a zone's script cannot be read: a flag that zone names
	 * is unknown, never absent
	 */
	public static ScriptFlags of(GameFiles game) throws IOException {
		GARC zo = game.archive(ArchiveType.ZONE_DATA);
		int zones = ZoneTables.zoneCount(zo, game.profile());
		java.util.Set<Integer> hashes = new java.util.HashSet<>();
		for (String n : FLAG_NATIVES) {
			hashes.add(GfHash.hashForName(n));
		}
		SortedSet<Integer> named = new TreeSet<>();
		for (int z = 0; z < zones; z++) {
			File edited = game.editedEntry(ArchiveType.ZONE_DATA, z);
			byte[] container = edited != null ? Files.readAllBytes(edited.toPath()) : zo.getDecompressedEntry(z);
			byte[] raw = container == null ? null : ctrmap.formats.containers.ContainerBytes.subfile(container, 2);
			if (raw == null) {
				throw new IOException("zone " + z + "'s script could not be read, so which flags it names is unknown");
			}
			GFLPawnScript s;
			try {
				s = new GFLPawnScript(raw);
				s.decompressThis();
			} catch (RuntimeException unreadable) {
				throw new IOException("zone " + z + "'s script could not be read (" + ctrmap.util.Bytes.reason(unreadable)
						+ "), so which flags it names is unknown", unreadable);
			}
			for (int k = 0; k < s.instructions.size(); k++) {
				PawnInstruction a = s.instructions.get(k);
				if (a.getCommand() != PawnInstruction.Commands.SYSREQ_N.ordinal() || a.argumentCells.length == 0
						|| a.argumentCells[0] < 0 || a.argumentCells[0] >= s.natives.size()) {
					continue;
				}
				int[] data = s.natives.get(a.argumentCells[0]).data;
				if (data.length < 2 || !hashes.contains(data[1])) {
					continue;
				}
				PawnInstruction b = k > 0 ? s.instructions.get(k - 1) : null;
				int bc = b == null ? -1 : b.getCommand();
				if (b != null && b.argumentCells.length > 0 && (bc == PawnInstruction.Commands.PUSH_C.ordinal()
						|| bc == PawnInstruction.Commands.PUSH_P_C.ordinal())) {
					named.add(b.argumentCells[0]);
				}
			}
		}
		if (named.isEmpty()) {
			throw new IOException("no zone script of " + zones + " names a flag - a read that found none is not a game with none");
		}
		return new ScriptFlags(named);
	}

	/** The highest flag any zone script names - as far as the save is known to hold flags. */
	public int highest() {
		return named.last();
	}

	/** Why {@code flag} must not be a one-time giver's, or null when no zone script names it. */
	public String refusal(int flag) {
		if (flag < 1) {
			return "Event flag " + flag + " is not a flag: they start at 1.";
		}
		if (flag > highest()) {
			return "Event flag " + flag + " is past " + highest() + ", the highest any zone script uses - which is as far "
					+ "as this editor can show the save holds flags.";
		}
		if (named.contains(flag)) {
			return "Event flag " + flag + " is already used by a zone script, so a one-time giver on it would be "
					+ "given away, or blocked, by whatever else sets it.";
		}
		return null;
	}

	/** The highest flag at or below {@link #highest} that no zone script names, or -1 when there is none. */
	public int suggestion() {
		for (int f = highest(); f >= 1; f--) {
			if (!named.contains(f)) {
				return f;
			}
		}
		return -1;
	}
}
