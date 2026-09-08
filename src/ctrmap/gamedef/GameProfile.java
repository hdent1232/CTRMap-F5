package ctrmap.gamedef;

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
 *     verified-feature flags, measured counts, detection.</li>
 * </ul>
 *
 * <p>Numbers in a profile must be MEASURED against that game's dump (or taken
 * from an established reference like pk3DS's GARCReference tables and marked
 * so). A profile method returning null/-1/false means "not present or not yet
 * verified for this game" - callers must treat that as absence, never guess.
 *
 * <p>That last sentence is the whole point of asking a profile instead of
 * asking which game is loaded. An {@code isXY()} gate answers false for
 * Sun/Moon, and a caller that reads false as "then it is ORAS" hands Sun/Moon
 * ORAS's warp labels, ORAS's move codes and ORAS's archive-tail arithmetic,
 * silently and with nobody having chosen it. Every method here has a third
 * answer - "nobody measured that" - and a caller must refuse in words when it
 * gets one.
 *
 * <p>Nothing in this package may reference the application's global session
 * holder in the {@code ctrmap} package. The seam exists to replace that
 * global, and a profile reaching back into it to find out which game it is
 * would close the loop it was built to open. The dependency runs one way: the
 * application asks for its profile, never the reverse.
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
		ITEM_DESCRIPTIONS,
		TRAINER_CLASS_NAMES,
		TRAINER_NAMES
	}

	/**
	 * Which edition of a game a dump is. A game can ship in more than one, and
	 * the editions do not agree on everything: the ORAS Special Demo keeps its
	 * location names in a different GameText entry from retail ORAS, so an
	 * editor that knows only "it is ORAS" reads the wrong table.
	 *
	 * <p>RETAIL is the answer for every game that has no second edition, which
	 * is why {@link #detectVariant} defaults to it: a game nobody has probed
	 * for a demo is a retail dump, not an unknown one.
	 */
	public enum Variant {
		RETAIL,
		DEMO
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
		CODE_PATCHES,
		/**
		 * Item records: reading and WRITING the item table.
		 *
		 * <p>Gated separately from the archive path on purpose. XY's item
		 * archive is a CITED location (pk3DS's GARCReference_XY) that nobody
		 * has measured against an XY dump here, and the profile says so - so a
		 * path exists that is good enough to look at and not good enough to
		 * write through. This flag is the difference. It is what the item
		 * editor asks before it opens, so an unverified game gets a sentence
		 * saying why rather than a writer poking 36 bytes into a guess.
		 */
		ITEM_EDITING,
		/**
		 * Giving one zone its OWN copy of an area or of its map geometry, so
		 * editing it stops changing every other zone that shares it -
		 * {@code AreaForker}, {@code GeometryForker} and the prompt in front of
		 * them, plus the shared-area scan {@code BchTexturePack.zonesUsingArea}
		 * that refuses a texture carry into an area somebody else is using.
		 *
		 * <p>A fork appends to AreaData, the NPC registry, FieldData and the
		 * MapMatrix, repoints the zone in two places, and grows the engine's
		 * global per-area table. Every one of those offsets was measured on
		 * ORAS. Turning this on for another game means measuring them again
		 * there, not assuming the layout carried over.
		 */
		AREA_FORK,
		/**
		 * Adding zones past the ones the game shipped - {@code ZoneAppender}
		 * and the paired {@code ZoneLimitPatch}, plus the repurpose scanner
		 * that finds retail zones safe to overwrite instead.
		 *
		 * <p>Needs the zone count the executable was built with AND the code
		 * patch that raises it, so it is inseparable from having RE'd that
		 * game's code.bin. Kept apart from {@link #CODE_PATCHES} because that
		 * flag is about being able to patch at all; this one is about the zone
		 * table specifically.
		 */
		ZONE_APPEND,
		/**
		 * Wild encounter tables (the "EN" pack the zone archive carries).
		 *
		 * <p>Their record layout and the archive slot they live in were
		 * measured on ORAS; nobody has checked either against another game.
		 */
		ENCOUNTERS
	}

	public abstract GameType type();

	public abstract String displayName();

	/**
	 * RomFS-relative path of an archive ("/a/0/3/9"), or null when this game
	 * lacks the archive or its location is not yet verified.
	 */
	public abstract String archivePath(ArchiveType t);

	/** GameText entry index for a table, or -1 when unknown for this game. */
	public abstract int textIndex(TextIndex t);

	/**
	 * The same, for a dump of a particular edition. Defaults to ignoring the
	 * variant, because most games have only one; a game that ships a demo with
	 * a different table order overrides this (see {@link OrasProfile}).
	 *
	 * <p>Callers with a session open should use this rather than
	 * {@link #textIndex(TextIndex)}, passing the session's own variant. It
	 * replaces the {@code isOA() && isOADemo()} special case that used to sit
	 * in {@code LocationNames}, where a second game shipping a demo would have
	 * had to add a second special case beside it.
	 */
	public int textIndex(TextIndex t, Variant v) {
		return textIndex(t);
	}

	public abstract boolean supports(Feature f);

	/**
	 * RomFS-relative path of a file whose existence identifies this game
	 * (the detection probe), or null when detection data is not yet known.
	 */
	public abstract String detectFile();

	/**
	 * Which edition of THIS game the dump in {@code gameDir} is. RETAIL unless
	 * a profile knows how to recognise another edition of itself.
	 *
	 * <p>Lives here rather than in the caller because "is this dump a demo" is
	 * a per-game question with a per-game answer, and both places that used to
	 * answer it - the open session and the static delegator in front of it -
	 * did it by building a File out of {@code OrasProfile.DEMO_PROBE} from
	 * outside the seam. Two costs, both measured: the probe path was reachable
	 * (and copyable) from anywhere, and
	 * javac inlines a {@code static final String} into every reader, so the
	 * romfs path "/a/3/0/0" sat in the constant pool of both of those classes
	 * even though neither source file spells a GARC path - invisible to the
	 * source-level seam guard, and found by ClassFileScannerTest reading the
	 * bytecode. A method call cannot be inlined that way.
	 */
	public Variant detectVariant(File gameDir) {
		return Variant.RETAIL;
	}

	// ---- measured per-game counts -----------------------------------------
	//
	// Each returns -1 for "nobody has measured this for this game". A caller
	// that gets -1 must refuse in words. It must NOT fall back to another
	// game's number: that is exactly the defect these replace, where
	// "archive.length - (isXY() ? 1 : 2)" handed Sun/Moon ORAS's answer.

	/**
	 * How many entries at the END of the ZONE_DATA archive are not zones, so
	 * {@code archive.length - this} is the zone count and the first of them is
	 * the master zone-header table. -1 when not measured for this game.
	 */
	public int zoneDataTrailingEntries() {
		return -1;
	}

	/**
	 * How many entries at the END of the AREA_DATA archive are not areas, so
	 * {@code archive.length - this} is the area count and the first of them is
	 * the engine's global per-area table. -1 when not measured for this game.
	 */
	public int areaDataTrailingEntries() {
		return -1;
	}

	/**
	 * How many subfiles a plain FIELD_DATA region container (a "GR") holds, so
	 * a newly created region can be built with the right number of slots. -1
	 * when not measured for this game.
	 */
	public int fieldDataSubfileCount() {
		return -1;
	}

	/**
	 * True when a zone header stores the zone's own index inside the top bits
	 * of its unknownFlags word, so cloning a zone into another slot has to
	 * rewrite it. False when the game does not do that - and false is also the
	 * answer for a game nobody has measured, deliberately: copying the word
	 * verbatim preserves whatever it holds, while rewriting bits of a field
	 * whose layout is a guess corrupts it.
	 */
	public boolean zoneNumberInUnknownFlags() {
		return false;
	}

	/**
	 * True when setting a zone header's CYCLING flag is safe on this game.
	 *
	 * <p>False is both "this game hangs when you do" and "nobody has tried it
	 * here", and the two want the same behaviour: do not set the bit. ORAS
	 * softlocks on a bike in an interior even under an emulator, X/Y does not,
	 * and that difference was written into the Extras panel as
	 * {@code header.enableCycling = Workspace.isXY()} - a game-identity test
	 * that answered "no" for Sun/Moon by accident rather than by measurement.
	 * The answer is the same; where it is decided is the point.
	 */
	public boolean cyclingFlagSafe() {
		return false;
	}

	/**
	 * The 3DS title id of this game's first version - Omega Ruby of ORAS, X of
	 * X/Y - or null when no id has been recorded for this game.
	 *
	 * <p>A profile covers a VERSION PAIR, so this is only ever a default: the
	 * mod deployer prefers the RomFS folder's own name when that looks like a
	 * title id, because that is the one the user actually dumped. Null here
	 * means the deployer offers no folder rather than the wrong one - it used
	 * to answer {@code isXY() ? X : Omega Ruby}, which pointed a Sun/Moon mod
	 * at Omega Ruby's emulator directory, where the game it was built for would
	 * never look and the game it names might.
	 */
	public String titleId() {
		return null;
	}

	// ---- registry ----------------------------------------------------------

	private static final GameProfile[] ALL = {
		new OrasProfile(), new XyProfile(), new SmProfile(), new UsumProfile()
	};

	public static GameProfile of(GameType g) {
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
			String sound = p.archivePath(ArchiveType.SOUND_BCSAR);
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
}
