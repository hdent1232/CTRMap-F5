package ctrmap.formats.npcreg;

import ctrmap.formats.GameCache;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.MM;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.H3DModelNameGet;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The GAME-WIDE pool of overworld NPC models (MoveModels, a/0/2/1). Every area's
 * {@link NPCRegistry} is only a small per-area lookup into this shared pool, so
 * to use "any NPC anywhere" the model picker browses this pool and
 * {@link NPCRegistry#registerModel} adds the chosen one to the current area.
 *
 * <p>Names come from each model's own BCH (via {@link H3DModelNameGet}, a cheap
 * header read - no full parse), read from the handed game's staged copy of
 * each entry so an edited model is named by what the workspace holds. The list
 * is read once per game and kept in that game's own store (F218), which a pack
 * empties: it promised to be dropped "when the archives were repacked" through
 * an {@code invalidate()} nothing called, so a pack never reached it.
 *
 * <p>Handed its game rather than fetching the application's: the pool used to
 * read the global's archive and workspace files, so it could only ever list
 * the application's models, and asked before a workspace was open it cached an
 * empty list for the rest of the session. {@code ctrmap.tests.HandedGameTest}
 * hands it two games and reads two lists.
 */
public class MoveModelPool {

	/** MoveModels index -> internal model name (may be null), kept by the game it was read from. */
	private static final GameCache.Key<List<String>> NAMES = new GameCache.Key<>("move-model names");

	/** Number of models in the handed game's pool (read and kept on first call); 0 when it has no MoveModels archive open. */
	public static int size(GameFiles files) {
		return names(handed(files)).size();
	}

	/** Internal BCH model name for a MoveModels index of the handed game, or null if unknown. */
	public static String name(GameFiles files, int index) {
		List<String> names = names(handed(files));
		return index >= 0 && index < names.size() ? names.get(index) : null;
	}

	private static List<String> names(GameFiles files) {
		return files.cache().get(NAMES, () -> read(files));
	}

	/** A handed game must exist: null here would be a pool listed for no game at all. */
	private static GameFiles handed(GameFiles files) {
		if (files == null) {
			throw new IllegalArgumentException("the move-model pool must be handed the game whose models it lists;"
					+ " handed null, there is no archive to read and no game to answer for");
		}
		return files;
	}

	private static List<String> read(GameFiles files) {
		List<String> loaded = new ArrayList<>();
		GARC pool = files.archive(ArchiveType.MOVE_MODELS);
		if (pool != null) {
			for (int i = 0; i < pool.length; i++) {
				String nm = null;
				try {
					File staged = files.staged(ArchiveType.MOVE_MODELS, i);
					byte[] bch = staged == null ? null : new MM(staged, files).getFile(0);
					if (bch != null) {
						nm = H3DModelNameGet.H3DModelNameGet(bch);
					}
				} catch (Exception ex) {
					// leave this model's name null; it is still selectable by index
				}
				loaded.add(nm != null ? nm.trim() : null);
			}
		}
		return Collections.unmodifiableList(loaded);
	}
}
