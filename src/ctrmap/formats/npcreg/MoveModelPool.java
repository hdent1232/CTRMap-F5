package ctrmap.formats.npcreg;

import ctrmap.Workspace;
import ctrmap.formats.containers.MM;
import ctrmap.formats.h3d.H3DModelNameGet;
import java.util.ArrayList;
import java.util.List;

/**
 * The GAME-WIDE pool of overworld NPC models (MoveModels, a/0/2/1). Every area's
 * {@link NPCRegistry} is only a small per-area lookup into this shared pool, so
 * to use "any NPC anywhere" the model picker browses this pool and
 * {@link NPCRegistry#registerModel} adds the chosen one to the current area.
 *
 * <p>Names come from each model's own BCH (via {@link H3DModelNameGet}, a cheap
 * header read - no full parse). The list is loaded once and cached for the
 * session; {@link #invalidate()} clears it on a workspace change.
 */
public class MoveModelPool {

	private static List<String> names; // MoveModels index -> internal model name (may be null)

	/** Number of models in the pool (loads + caches on first call). */
	public static synchronized int size() {
		ensureLoaded();
		return names == null ? 0 : names.size();
	}

	/** Internal BCH model name for a MoveModels index, or null if unknown. */
	public static synchronized String name(int index) {
		ensureLoaded();
		return (names != null && index >= 0 && index < names.size()) ? names.get(index) : null;
	}

	/** Drop the cache (e.g. when a different workspace is loaded). */
	public static synchronized void invalidate() {
		names = null;
	}

	private static void ensureLoaded() {
		if (names != null) {
			return;
		}
		names = new ArrayList<>();
		try {
			int count = Workspace.getArchive(Workspace.ArchiveType.MOVE_MODELS).length;
			for (int i = 0; i < count; i++) {
				String nm = null;
				try {
					byte[] bch = new MM(Workspace.getWorkspaceFile(Workspace.ArchiveType.MOVE_MODELS, i)).getFile(0);
					if (bch != null) {
						nm = H3DModelNameGet.H3DModelNameGet(bch);
					}
				} catch (Exception ex) {
					// leave this model's name null; it is still selectable by index
				}
				names.add(nm != null ? nm.trim() : null);
			}
		} catch (Exception ex) {
			// partial/empty pool is fine - the picker just shows fewer names
		}
	}
}
