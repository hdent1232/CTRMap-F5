package ctrmap.formats.maison;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
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
 * <p>Ground truth is the workspace's one-time pristine snapshot
 * ({@link Workspace#originalSnapshotDir()}); when a workspace predates the
 * snapshot mechanism the guard degrades to "whatever is non-empty NOW is
 * treated as retail" - conservative in the safe direction.
 */
public class MaisonPoolGuard {

	/** The pristine sets, or null when no snapshot exists. */
	public final MaisonSet[] vanilla;
	/** Per-slot: true = retail data (non-empty in the snapshot, or referenced
	 *  by a retail class list); editing it changes shipped gameplay. */
	public final boolean[] vanillaUsed;
	/** True when the snapshot was available (labels are exact, not inferred). */
	public final boolean exact;

	private MaisonPoolGuard(MaisonSet[] vanilla, boolean[] used, boolean exact) {
		this.vanilla = vanilla;
		this.vanillaUsed = used;
		this.exact = exact;
	}

	/**
	 * Builds the guard for one pool. {@code pairedList} is the class-list
	 * archive whose rows reference this pool (null for pool C, which has no
	 * list table). {@code current} - the pool as loaded in the editor - is the
	 * fallback when no snapshot exists.
	 */
	public static MaisonPoolGuard load(Workspace.ArchiveType pool, Workspace.ArchiveType pairedList, MaisonSet[] current) {
		MaisonSet[] vanilla = readSnapshotPool(pool);
		if (vanilla != null && vanilla.length != current.length) {
			vanilla = null; //truncated/mismatched snapshot - do not trust it
		}
		if (vanilla == null) {
			//fail CLOSED: without a trustworthy snapshot, whatever is non-empty
			//NOW is treated as retail (guarding too much, never too little)
			boolean[] used = new boolean[current.length];
			for (int i = 0; i < current.length; i++) {
				used[i] = current[i] != null && !current[i].isEmpty();
			}
			return new MaisonPoolGuard(null, used, false);
		}
		Set<Integer> referenced = pairedList != null ? readSnapshotReferences(pairedList) : new HashSet<Integer>();
		boolean[] used = new boolean[vanilla.length];
		for (int i = 0; i < vanilla.length; i++) {
			used[i] = (vanilla[i] != null && !vanilla[i].isEmpty()) || referenced.contains(i);
		}
		return new MaisonPoolGuard(vanilla, used, true);
	}

	/** The first slot that is free to author in (empty now AND not retail), or -1. */
	public int firstFreeSlot(MaisonSet[] current) {
		for (int i = 0; i < current.length && i < vanillaUsed.length; i++) {
			if (!vanillaUsed[i] && (current[i] == null || current[i].isEmpty())) {
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

	/** Opens a pool GARC from the pristine snapshot, or null when unavailable
	 *  or unparseable (the GARC parser swallows read errors and can hand back
	 *  an empty shell - treat that as "no snapshot", never as "all free"). */
	private static MaisonSet[] readSnapshotPool(Workspace.ArchiveType pool) {
		try {
			GARC g = snapshotGarc(pool);
			if (g == null || g.length == 0) {
				return null;
			}
			MaisonSet[] out = new MaisonSet[g.length];
			for (int i = 0; i < g.length; i++) {
				byte[] rec = g.getDecompressedEntry(i);
				if (rec == null) {
					return null; //partial snapshot - untrustworthy
				}
				out[i] = MaisonSet.read(rec);
			}
			return out;
		} catch (Exception ex) {
			return null;
		}
	}

	/** Every set index any retail class-list row references, from the snapshot. */
	private static Set<Integer> readSnapshotReferences(Workspace.ArchiveType listTable) {
		Set<Integer> refs = new HashSet<>();
		try {
			GARC g = snapshotGarc(listTable);
			if (g == null) {
				return refs;
			}
			for (int i = 0; i < g.length; i++) {
				try {
					MaisonClassList l = MaisonClassList.read(g.getDecompressedEntry(i));
					refs.addAll(l.setIndices);
				} catch (Exception ignore) {
				}
			}
		} catch (Exception ignore) {
		}
		return refs;
	}

	/** The pristine class lists of a table, or null when no snapshot exists -
	 *  the class-assignment dialog's "restore retail row" source. */
	public static MaisonClassList[] readSnapshotLists(Workspace.ArchiveType listTable) {
		try {
			GARC g = snapshotGarc(listTable);
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

	private static GARC snapshotGarc(Workspace.ArchiveType t) throws Exception {
		String rel = Workspace.getArchivePath(t, Workspace.game);
		if (rel == null) {
			return null;
		}
		File f = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
		if (!f.exists()) {
			return null;
		}
		return new GARC(f, false);
	}
}
