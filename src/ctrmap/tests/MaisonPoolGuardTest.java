package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.maison.MaisonPoolGuard;
import ctrmap.formats.maison.MaisonSet;
import java.io.File;

/**
 * Verifies the vanilla-safety model of the battle facility opponent editor:
 * <ol>
 * <li>the no-snapshot fallback treats exactly the non-empty CURRENT rows as
 *     retail (conservative in the safe direction) and finds free slots;</li>
 * <li>with a real pristine snapshot, retail rows = non-empty-in-snapshot union
 *     class-list-referenced, matching the measured retail occupancy
 *     (217/873/36 of 999), every pool keeps free authoring space, and the
 *     first free slot is genuinely unreferenced and empty.</li>
 * </ol>
 * The snapshot half runs only when the workspace's _original_garcs exists
 * (arg 1 overrides the workspace path).
 */
public class MaisonPoolGuardTest {

	public static void main(String[] args) throws Exception {
		int failures = 0;

		// ---- 1) fallback path: no snapshot available -----------------------
		String realWs = args.length > 0 ? args[0]
				: "../Workspace";
		String savedWs = Workspace.WORKSPACE_PATH;
		Workspace.GameType savedGame = Workspace.game;
		Workspace.WORKSPACE_PATH = "Z:/ctrmap-test-missing-path";
		Workspace.game = Workspace.GameType.ORAS;
		MaisonSet[] cur = new MaisonSet[10];
		for (int i = 0; i < cur.length; i++) {
			cur[i] = new MaisonSet();
			if (i < 5) {
				cur[i].species = 1 + i;
			}
		}
		MaisonPoolGuard fb = MaisonPoolGuard.load(Workspace.ArchiveType.MAISON_SET_POOL_A,
				Workspace.ArchiveType.MAISON_CLASS_LIST_A, cur);
		if (fb.exact) {
			failures++;
			System.out.println("FAIL fallback guard claims snapshot exactness");
		}
		for (int i = 0; i < cur.length; i++) {
			if (fb.vanillaUsed[i] != (i < 5)) {
				failures++;
				System.out.println("FAIL fallback vanillaUsed[" + i + "]=" + fb.vanillaUsed[i]);
			}
		}
		if (fb.firstFreeSlot(cur) != 5) {
			failures++;
			System.out.println("FAIL fallback firstFreeSlot=" + fb.firstFreeSlot(cur) + " (want 5)");
		}

		// ---- 2) exact path against the real pristine snapshot --------------
		Workspace.WORKSPACE_PATH = realWs;
		Workspace.game = Workspace.GameType.ORAS;
		File snap = Workspace.originalSnapshotDir();
		Workspace.ArchiveType[] pools = {Workspace.ArchiveType.MAISON_SET_POOL_A,
			Workspace.ArchiveType.MAISON_SET_POOL_B, Workspace.ArchiveType.MAISON_SET_POOL_C};
		Workspace.ArchiveType[] lists = {Workspace.ArchiveType.MAISON_CLASS_LIST_A,
			Workspace.ArchiveType.MAISON_CLASS_LIST_B, null};
		// measured retail occupancy (non-empty sets; referenced adds nothing new
		// per the linkage invariant "0 refs to empty sets")
		int[] wantUsed = {217, 873, 36};
		boolean snapshotRan = false;
		for (int p = 0; p < pools.length; p++) {
			String rel = Workspace.getArchivePath(pools[p], Workspace.game);
			if (rel == null || !new File(snap.getAbsolutePath() + rel).exists()) {
				continue;
			}
			snapshotRan = true;
			MaisonSet[] empty999 = new MaisonSet[999];
			for (int i = 0; i < empty999.length; i++) {
				empty999[i] = new MaisonSet();
			}
			MaisonPoolGuard g = MaisonPoolGuard.load(pools[p], lists[p], empty999);
			if (!g.exact) {
				failures++;
				System.out.println("FAIL pool " + p + " snapshot present but guard fell back");
				continue;
			}
			int used = g.vanillaUsed.length - g.freeCount();
			if (used != wantUsed[p]) {
				failures++;
				System.out.println("FAIL pool " + p + " retail rows=" + used + " (want " + wantUsed[p] + ")");
			}
			if (g.freeCount() <= 0) {
				failures++;
				System.out.println("FAIL pool " + p + " has no free authoring space");
			}
			int free = g.firstFreeSlot(empty999);
			if (free < 0 || g.vanillaUsed[free]
					|| (g.vanilla != null && g.vanilla[free] != null && !g.vanilla[free].isEmpty())) {
				failures++;
				System.out.println("FAIL pool " + p + " firstFreeSlot=" + free + " is not actually free");
			}
			System.out.println("pool " + p + ": " + used + " retail, " + g.freeCount() + " free, first free = " + free);
		}
		if (!snapshotRan) {
			System.out.println("note: no pristine snapshot found - exact-path checks skipped");
		}

		Workspace.WORKSPACE_PATH = savedWs;
		Workspace.game = savedGame;
		System.out.println(failures == 0 ? "MaisonPoolGuardTest: ALL OK" : "MaisonPoolGuardTest: " + failures + " FAILURES");
		if (failures > 0) {
			System.exit(1);
		}
	}
}
