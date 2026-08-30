package ctrmap.gamedef;

import ctrmap.Workspace;
import java.io.File;

/**
 * Everything the editor knows about ONE game version, in one place - the seam
 * that keeps the shared engine game-agnostic. The rule (enforced by
 * SourceSeamTest): no RomFS path, GameText entry index, or other game-detected
 * constant may live anywhere in the editor source outside this package.
 *
 * <p>Layering (see ARCHITECTURE.md):
 * <ul>
 * <li><b>Universal engine</b> - GARC/LZ11, the GF container family, the text
 *     cipher, patricia dicts, the GF name hash, the UI/workspace machinery:
 *     shared by every 3DS Pokemon game.</li>
 * <li><b>Gen 6 format layer</b> - BCH ("H3D") models and everything built on
 *     them (map painter, prefabs, previews, world animations, fog): shared by
 *     XY and ORAS; Gen 7 (SM/USUM) replaced models with GFModel/GFMotion and
 *     needs its own layer.</li>
 * <li><b>Per-game profiles</b> (this package) - archive paths, text indices,
 *     verified-feature flags, detection.</li>
 * </ul>
 *
 * <p>Numbers in a profile must be MEASURED against that game's dump (or taken
 * from an established reference like pk3DS's GARCReference tables and marked
 * so). A profile method returning null/-1/false means "not present or not yet
 * verified for this game" - callers must treat that as absence, never guess.
 */
public abstract class GameProfile {

	/** GameText tables the editor looks up by entry index. */
	public enum TextIndex {
		LOCATION_NAMES,
		SPECIES_NAMES,
		MOVE_NAMES,
		TYPE_NAMES,
		ABILITY_NAMES,
		ITEM_NAMES,
		TRAINER_CLASS_NAMES,
		TRAINER_NAMES
	}

	/** Editor capabilities, gated per game on what has been RE'd AND verified. */
	public enum Feature {
		/** BCH map models: viewer, geometry editing, prefabs, OBJ import. */
		H3D_MAPS,
		/** The tile painter (terrain brushes, elevation, ramps, edge strips). */
		TILE_PAINTER,
		/** Area fog/ambient editing + the GameFreak atmosphere picker. */
		AREA_ENV,
		/** The water-scroll animation splice (WorldAnim). */
		WATER_SPLICE,
		/** Trainer team/class editing. */
		TRAINER_EDITING,
		/** Battle Maison opponent pools. */
		MAISON,
		/** Pokemon reference previews (stats, types, move data). */
		POKE_PREVIEWS,
		/** Script disassembly with named natives (a natives table exists). */
		SCRIPT_NATIVES,
		/** code.bin patches (addresses known for this game's executable). */
		CODE_PATCHES
	}

	public abstract Workspace.GameType type();

	public abstract String displayName();

	/**
	 * RomFS-relative path of an archive ("/a/0/3/9"), or null when this game
	 * lacks the archive or its location is not yet verified.
	 */
	public abstract String archivePath(Workspace.ArchiveType t);

	/** GameText entry index for a table, or -1 when unknown for this game. */
	public abstract int textIndex(TextIndex t);

	public abstract boolean supports(Feature f);

	/**
	 * RomFS-relative path of a file whose existence identifies this game
	 * (the detection probe), or null when detection data is not yet known.
	 */
	public abstract String detectFile();

	// ---- registry ----------------------------------------------------------

	private static final GameProfile[] ALL = {
		new OrasProfile(), new XyProfile(), new SmProfile(), new UsumProfile()
	};

	public static GameProfile of(Workspace.GameType g) {
		for (GameProfile p : ALL) {
			if (p.type() == g) {
				return p;
			}
		}
		throw new IllegalArgumentException("no profile for " + g);
	}

	/**
	 * Detects the game in a RomFS root, or null if none match.
	 *
	 * <p>Checks each game's SOUND ARCHIVE first, then falls back to the generic
	 * probe file. The order matters: X/Y's probe file is also present in an ORAS
	 * dump, so probe-only detection returned whichever profile happened to come
	 * first in the registry - correct today purely by luck of the array order,
	 * and silently wrong the moment anyone reordered it. Each game's sound
	 * archive is named after that game and appears in no other, so it is a real
	 * discriminator rather than a coincidence.
	 */
	public static GameProfile detect(File romfsRoot) {
		for (GameProfile p : ALL) {
			String sound = p.archivePath(Workspace.ArchiveType.SOUND_BCSAR);
			if (sound != null && new File(romfsRoot, sound).isFile()) {
				return p;
			}
		}
		for (GameProfile p : ALL) {
			String probe = p.detectFile();
			if (probe != null && new File(romfsRoot, probe).exists()) {
				return p;
			}
		}
		return null;
	}

	public static GameProfile current() {
		return of(Workspace.game);
	}
}
