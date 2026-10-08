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
		H3D_MAPS("Viewing maps and editing their geometry"),
		/** The tile painter (terrain brushes, elevation, ramps, edge strips). */
		TILE_PAINTER("The Map Builder's tile painter"),
		/** Area fog/ambient editing + the GameFreak atmosphere picker. */
		AREA_ENV("Area fog, light and atmosphere"),
		/** The water-scroll animation splice (WorldAnim). */
		WATER_SPLICE("Animated water"),
		/** Trainer team/class editing. */
		TRAINER_EDITING("Trainer editing"),
		/** Battle Maison opponent pools. */
		MAISON("Battle facility opponents"),
		/** Pokemon reference previews (stats, types, move data). */
		POKE_PREVIEWS("Pokemon reference previews"),
		/** Script disassembly with named natives (a natives table exists). */
		SCRIPT_NATIVES("Scripts with named natives"),
		/** code.bin patches (addresses known for this game's executable). */
		CODE_PATCHES("code.bin patches"),
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
		ITEM_EDITING("Item editing"),
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
		AREA_FORK("Giving a zone its own area or map"),
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
		ZONE_APPEND("Adding zones"),
		/**
		 * Wild encounter tables (the "EN" pack the zone archive carries).
		 *
		 * <p>Their record layout and the archive slot they live in were
		 * measured on ORAS; nobody has checked either against another game.
		 */
		ENCOUNTERS("Wild encounters"),
		/**
		 * Scripted NPCs written into a zone's script - talkers, signs, item and Battle Points
		 * givers, battle challenges: {@code ZoneScriptEdit} and every template it applies.
		 *
		 * <p>The templates reproduce call sites, argument layouts and native hashes measured on
		 * ORAS's zone scripts; their own class says "XY is NOT covered by the recon - callers
		 * should gate these templates", and nothing did (F085). A script the game runs wrong
		 * freezes the zone in game rather than failing in the editor.
		 */
		NPC_SCRIPTS("Talking NPCs, signs, givers and challenges"),
		/**
		 * The Extras free-camera mod: dummy camera collision written into every area's subfile 7,
		 * and the camera, failsafe and 3D flags set in every zone header and the per-area table.
		 *
		 * <p>The collision it writes is a bundled resource cut from X/Y's Lumiose City, and the mod
		 * has been offered on the reference game since upstream; nothing in this tree measured that
		 * subfile 7 or those flag offsets mean the same on another game, so a game is asked before
		 * the mod is offered there (F100).
		 */
		FREE_CAMERA("The free-camera mod");

		private final String label;

		Feature(String label) {
			this.label = label;
		}

		/**
		 * What the docs call it: the README's table of what works on which game is written from
		 * these and {@link GameProfile#supports}, and checked against them (F150). A feature cannot
		 * be added without one.
		 */
		public String label() {
			return label;
		}
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

	/** A retail battle facility whose lobby "Set up a Battle facility" copies. */
	public enum Facility {
		MAISON, INSTITUTE
	}

	/**
	 * The ZONE_DATA index of a retail battle facility's lobby, which "Set up a Battle facility"
	 * copies - script and entities verbatim. -1 when not measured for this game.
	 *
	 * <p>HERE AND NOT IN THE WINDOW (F009): the two numbers lived in CtrmapMainframe as private
	 * constants, ORAS facts in a class that names no game - the seam rule's "no game-detected
	 * constant outside ctrmap.gamedef", passed only because the scan read string literals.
	 */
	public int facilityLobbyZone(Facility f) {
		return -1;
	}

	/**
	 * How many entries at the END of the ZONE_DATA archive are not zones, so
	 * {@code archive.length - this} is the zone count and the first of them is
	 * the master zone-header table. -1 when not measured for this game.
	 */
	public int zoneDataTrailingEntries() {
		return -1;
	}

	/**
	 * The AREA_DATA index of the engine's global per-area table - not an area, but one row per
	 * area id. Every entry before it is an area, and so is every entry an area fork appends after
	 * it. It was "how many entries at the end are not areas", which holds for a retail dump and
	 * named the first forked area as the table once one was appended (F235). -1 when not
	 * measured for this game.
	 */
	public int areaTableIndex() {
		return -1;
	}

	/**
	 * The highest trainer id a script or an NPC may name: the trainer archives hold this many
	 * entries after the dummy at 0. ONE ANSWER (F085): it was spelled as 949 in the templates,
	 * the challenge form, the gauntlet wizard and the trainer editor's prompt. -1 when not
	 * measured for this game.
	 */
	public int trainerIdMax() {
		return -1;
	}

	/**
	 * The highest item id a giver may hand out: the item-name text has this many lines after the
	 * null item at 0 (F085). -1 when not measured for this game.
	 */
	public int itemIdMax() {
		return -1;
	}

	/**
	 * How many zones an unmodified dump of this game holds: the ZONE_DATA entries before the
	 * trailing ones. Every zone past it is one the editor appended. ONE ANSWER (F205): it was
	 * {@code ZoneLimitPatch.BASE_ZONES}, re-typed as 536 in the window's menus and messages, the
	 * zone list's and the remover's, and two harvesters. -1 when not measured for this game.
	 */
	public int retailZoneCount() {
		return -1;
	}

	/**
	 * The highest species id: the last national dex number this game ships (F205). The species
	 * text has this many lines after the empty one at 0. -1 when not measured for this game.
	 */
	public int speciesIdMax() {
		return -1;
	}

	/**
	 * The highest move id: the move-name text has this many lines after the null move at 0 (F205).
	 * -1 when not measured for this game.
	 */
	public int moveIdMax() {
		return -1;
	}

	/** The highest trainer class id a trainer may be given (F205). -1 when not measured for this game. */
	public int trainerClassMax() {
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
	 * The byte a camera's isNeutral field holds when the camera is a neutral zone (one that
	 * keeps the angle it is entered with), or -1 when nobody has recorded it for this game.
	 *
	 * <p>The camera form ticked a camera neutral as 2 on every game, under a comment saying
	 * ORAS uses 1 and X/Y 2, "but for now, 2 (XY) is the default" - on an editor whose
	 * reference game is ORAS (F097). The form prefers the value the area's own neutral
	 * cameras hold, which is a reading of this game's data; this is the answer when the area
	 * has none. -1 refuses the tick rather than guessing a byte.
	 */
	public int neutralCameraValue() {
		return -1;
	}

	/**
	 * The 3DS title ids of the versions this profile covers, each with its version's name, in
	 * order - empty when none has been recorded for this game.
	 *
	 * <p>A profile covers a VERSION PAIR, so none of these is "the" title: the mod deployer reads
	 * the dumped one from the dump and, when the dump does not say, asks which of these it is. It
	 * was one id, the pair's first, and the deployer fell back to it - so an Alpha Sapphire or Y
	 * dump in a folder not named by its id was deployed into Omega Ruby's or X's mods folder, where
	 * the game it was made for never looks (F238). Before that it was {@code isXY() ? X : Omega
	 * Ruby}, which pointed a Sun/Moon mod at Omega Ruby's emulator directory.
	 */
	public java.util.Map<String, String> titleIds() {
		return java.util.Collections.emptyMap();
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
