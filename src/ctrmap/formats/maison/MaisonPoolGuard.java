package ctrmap.formats.maison;

import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * The vanilla-safety model for the battle facility opponent editors: which set
 * slots of a pool are RETAIL (the shipped game battles with them - editing one
 * changes the retail facility AND every cloned facility, since the pools are
 * engine-wide) and which are FREE authoring space (empty in the pristine dump
 * and referenced by no retail class list - proven safe to fill: the retail
 * linkage corpus test shows zero references to empty sets).
 *
 * <p>Ground truth is the pristine snapshot of the game this guard is HANDED
 * ({@link GameFiles#pristine()}); when that game has no snapshot the guard
 * degrades to "whatever is non-empty NOW is treated as retail" - conservative
 * in the safe direction.
 *
 * <p>The game is a parameter, not fetched. This class used to resolve the
 * snapshot through the application's global session holder, so it could only
 * ever judge the one game the application had open and no suite could hand it
 * a pool of its own; handed its {@link GameFiles} it judges whatever it is
 * given, and a suite gives it a scratch game with no workspace open at all.
 */
public class MaisonPoolGuard {

	/** The pristine sets, or null when no snapshot exists. */
	public final MaisonSet[] vanilla;
	/** Per-slot: true = retail data (non-empty in the snapshot, or referenced
	 *  by a retail class list); editing it changes shipped gameplay. */
	public final boolean[] vanillaUsed;
	/** True when the snapshot was available (labels are exact, not inferred). */
	public final boolean exact;
	/** Why the labels are inferred, in words for the editor to show; null when they are exact. */
	public final String inexact;

	private MaisonPoolGuard(MaisonSet[] vanilla, boolean[] used, String inexact) {
		this.vanilla = vanilla;
		this.vanillaUsed = used;
		this.exact = inexact == null;
		this.inexact = inexact;
	}

	/**
	 * The fail-CLOSED guard: without a trustworthy snapshot, whatever is non-empty NOW is
	 * treated as retail (guarding too much, never too little), and {@code why} says so.
	 */
	private static MaisonPoolGuard inferred(MaisonSet[] current, String why) {
		boolean[] used = new boolean[current.length];
		for (int i = 0; i < current.length; i++) {
			used[i] = current[i] != null && !current[i].isEmpty();
		}
		return new MaisonPoolGuard(null, used, why);
	}

	/**
	 * The game a caller must hand this guard. Null is refused rather than
	 * treated as "no snapshot": the no-snapshot path below fails closed by
	 * design, and a caller that forgot to hand the game would ride it without
	 * a word, labelling every empty slot free on a game nobody looked at.
	 */
	private static GameFiles handed(GameFiles files) {
		if (files == null) {
			throw new IllegalArgumentException("the pool guard must be handed the game whose pristine"
					+ " snapshot says which sets are retail; handed null it would have to guess");
		}
		return files;
	}

	/**
	 * Builds the guard for one pool of the handed game. {@code pairedList} is
	 * the class-list archive whose rows reference this pool (null for pool C,
	 * which has no list table). {@code current} - the pool as loaded in the
	 * editor - is the fallback when the game has no snapshot.
	 */
	public static MaisonPoolGuard load(GameFiles files, ArchiveType pool, ArchiveType pairedList, MaisonSet[] current) {
		handed(files);
		//F211: a pool that would not read answered "no snapshot", and a class-list row that would not read
		//was skipped with its references - which labels an empty slot that retail row names FREE, the one
		//thing this guards. Each is now said, and a row that did not read keeps what is filled NOW retail
		MaisonSet[] vanilla;
		Set<Integer> referenced;
		java.util.List<String> problems = new java.util.ArrayList<>();
		try {
			vanilla = readSnapshotPool(files, pool);
			if (vanilla == null) {
				return inferred(current, "no pristine snapshot");
			}
			if (vanilla.length != current.length) {
				return inferred(current, "the pristine snapshot holds " + vanilla.length + " sets and the game "
						+ current.length);
			}
			referenced = pairedList != null ? readSnapshotReferences(files, pairedList, problems) : new HashSet<Integer>();
		} catch (Exception ex) {
			return inferred(current, "the pristine snapshot could not be read: " + ctrmap.util.Bytes.reason(ex));
		}
		boolean[] used = new boolean[vanilla.length];
		for (int i = 0; i < vanilla.length; i++) {
			used[i] = (vanilla[i] != null && !vanilla[i].isEmpty()) || referenced.contains(i)
					|| (!problems.isEmpty() && current[i] != null && !current[i].isEmpty());
		}
		return new MaisonPoolGuard(vanilla, used, problems.isEmpty() ? null
				: "the pristine class lists could not all be read: " + String.join(", ", problems));
	}

	/** The first slot that is free to author in (empty now AND not retail), or -1. */
	public int firstFreeSlot(MaisonSet[] current) {
		return firstFreeSlot(current, i -> false);
	}

	/**
	 * The same, passing over the slots {@code skip} names: a record the editor could not read shows
	 * as empty and is not free - authoring into it would write over the bytes it holds (F102).
	 */
	public int firstFreeSlot(MaisonSet[] current, java.util.function.IntPredicate skip) {
		for (int i = 0; i < current.length && i < vanillaUsed.length; i++) {
			if (!skip.test(i) && !vanillaUsed[i] && (current[i] == null || current[i].isEmpty())) {
				return i;
			}
		}
		return -1;
	}

	/** How many slots are free authoring space (regardless of current content). */
	public int freeCount() {
		int n = 0;
		for (boolean b : vanillaUsed) {
			if (!b) {
				n++;
			}
		}
		return n;
	}

	/** A pool GARC's sets from the pristine snapshot, or null when the snapshot has no such
	 *  pool (or an empty shell - the GARC parser can hand one back - which is "no snapshot",
	 *  never "all free"). THROWS when it is there and cannot be read. */
	private static MaisonSet[] readSnapshotPool(GameFiles files, ArchiveType pool) throws Exception {
		GARC g = snapshotGarc(files, pool);
		if (g == null || g.length == 0) {
			return null;
		}
		MaisonSet[] out = new MaisonSet[g.length];
		for (int i = 0; i < g.length; i++) {
			byte[] rec = g.getDecompressedEntry(i);
			if (rec == null) {
				throw new java.io.IOException(pool + " set " + i + " is missing from the snapshot");
			}
			out[i] = MaisonSet.read(rec);
		}
		return out;
	}

	/** Every set index any retail class-list row references, from the snapshot; none when the
	 *  snapshot holds no such table. A row that cannot be read is named in {@code problems} -
	 *  it took its references with it, and they are the ones that keep a slot from reading free. */
	private static Set<Integer> readSnapshotReferences(GameFiles files, ArchiveType listTable,
			java.util.List<String> problems) throws Exception {
		Set<Integer> refs = new HashSet<>();
		GARC g = snapshotGarc(files, listTable);
		if (g == null) {
			return refs;
		}
		for (int i = 0; i < g.length; i++) {
			try {
				refs.addAll(MaisonClassList.read(g.getDecompressedEntry(i)).setIndices);
			} catch (RuntimeException ex) {
				problems.add(listTable + " row " + i + " (" + ctrmap.util.Bytes.reason(ex) + ")");
			}
		}
		return refs;
	}

	/** The pristine class lists of a table of the handed game, or null when
	 *  that game has no snapshot - the class-assignment dialog's "restore
	 *  retail row" source. */
	public static MaisonClassList[] readSnapshotLists(GameFiles files, ArchiveType listTable) {
		handed(files);
		try {
			GARC g = snapshotGarc(files, listTable);
			if (g == null || g.length == 0) {
				return null;
			}
			MaisonClassList[] out = new MaisonClassList[g.length];
			for (int i = 0; i < g.length; i++) {
				byte[] rec = g.getDecompressedEntry(i);
				if (rec == null) {
					return null; //partial snapshot - untrustworthy
				}
				out[i] = MaisonClassList.read(rec);
			}
			return out;
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * A table's archive in the handed game's pristine snapshot, or null when
	 * that game lacks the table, took no snapshot, or the snapshot lacks it.
	 * Each null is the profile's or the snapshot's own answer; nothing is
	 * guessed from another game.
	 */
	private static GARC snapshotGarc(GameFiles files, ArchiveType t) throws Exception {
		File f = files.pristineArchive(t);   //the one pristine reader (F232)
		return f == null ? null : new GARC(f, false);
	}
}
