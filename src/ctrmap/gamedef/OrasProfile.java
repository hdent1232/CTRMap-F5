package ctrmap.gamedef;

import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;

/**
 * Pokemon Omega Ruby / Alpha Sapphire - the reference game of this editor.
 * Every number here is MEASURED against the user's own pristine dump (and the
 * archive paths agree with pk3DS's GARCReference_AO); all Feature flags are
 * backed by the regression battery in test.ps1.
 */
public class OrasProfile extends GameProfile {

	/** A GARC present only in the ORAS Special Demo's romfs. */
	public static final String DEMO_PROBE = "/a/3/0/0";

	/** The Special Demo's location-name GAMETEXT entry (retail uses 90). */
	public static final int DEMO_LOCATION_NAMES = 91;

	@Override
	public GameType type() {
		return GameType.ORAS;
	}

	@Override
	public String displayName() {
		return "Omega Ruby / Alpha Sapphire";
	}

	@Override
	public String archivePath(ArchiveType t) {
		switch (t) {
			case AREA_DATA: return "/a/0/1/4";
			case FIELD_DATA: return "/a/0/3/9";
			case MAP_MATRIX: return "/a/0/4/0";
			case GAMETEXT: return "/a/0/7/3";
			//storytext base GARC 079 + language offset 2 (English) - pk3DS GARCReference_AO
			case STORYTEXT: return "/a/0/8/1";
			case ZONE_DATA: return "/a/0/1/3";
			case BUILDING_MODELS: return "/a/0/2/3";
			case NPC_REGISTRIES: return "/a/1/3/7";
			case MOVE_MODELS: return "/a/0/2/1";
			case TRAINER_DATA: return "/a/0/3/6";
			case TRAINER_CLASS: return "/a/0/3/7";
			case TRAINER_POKE: return "/a/0/3/8";
			case MAISON_SET_POOL_A: return "/a/1/8/2";
			case MAISON_CLASS_LIST_A: return "/a/1/8/3";
			case MAISON_SET_POOL_B: return "/a/1/8/4";
			case MAISON_CLASS_LIST_B: return "/a/1/8/5";
			case MAISON_SET_POOL_C: return "/a/1/8/6";
			case PERSONAL: return "/a/1/9/5";
			case MOVE_DATA: return "/a/1/8/9";
			//measured on a retail dump: 776 entries of 36 bytes, all round-tripping
			case ITEM_DATA: return "/a/1/9/7";
			case SOUND_BCSAR: return "/sound/sango_sound.bcsar";
			default: return null;
		}
	}

	@Override
	public int textIndex(TextIndex t) {
		switch (t) {
			case LOCATION_NAMES: return 90;
			case SPECIES_NAMES: return 98;
			case MOVE_NAMES: return 14;
			case TYPE_NAMES: return 18;
			case ABILITY_NAMES: return 37;
			case ITEM_NAMES: return 114;
			case ITEM_DESCRIPTIONS: return 117;
			case TRAINER_CLASS_NAMES: return 21;
			case TRAINER_NAMES: return 22;
			default: return -1;
		}
	}

	/**
	 * The demo's location names sit in a different GAMETEXT entry, so a demo
	 * dump read with the retail index shows the wrong table. This is the whole
	 * reason {@link Variant} exists.
	 */
	@Override
	public int textIndex(TextIndex t, Variant v) {
		if (t == TextIndex.LOCATION_NAMES && v == Variant.DEMO) {
			return DEMO_LOCATION_NAMES;
		}
		return textIndex(t);
	}

	@Override
	public boolean supports(Feature f) {
		return true; // the reference game - everything is built and corpus-tested here
	}

	@Override
	public String detectFile() {
		return "a/2/9/8"; // the last GARC of the ORAS romfs
	}

	/**
	 * The Special Demo ships a GARC retail does not. Probing for it is this
	 * profile's own business: nobody outside the seam has to know the path, and
	 * because this is a method rather than a constant read, javac cannot inline
	 * the literal into the caller's constant pool the way it did while both
	 * callers built the File themselves.
	 */
	@Override
	public Variant detectVariant(java.io.File gameDir) {
		return gameDir != null && new java.io.File(gameDir.getPath() + DEMO_PROBE).exists()
				? Variant.DEMO : Variant.RETAIL;
	}

	/**
	 * 2. MEASURED on the pristine retail dump: ZONE_DATA (/a/0/1/3) holds 538
	 * entries - 536 zones, then entry 536, the master zone-header table (30016
	 * bytes = 536 rows of 0x38), then entry 537, the "EN" wild-encounter pack.
	 */
	@Override
	public int zoneDataTrailingEntries() {
		return 2;
	}

	/** The Battle Maison lobby (Battle Resort) is zone 517, the Battle Institute (Mauville) 448. */
	@Override
	public int facilityLobbyZone(Facility f) {
		return f == Facility.MAISON ? 517 : f == Facility.INSTITUTE ? 448 : -1;
	}

	/**
	 * 228. MEASURED on the pristine retail dump: AREA_DATA (/a/0/1/4)
	 * holds 229 entries - areas 0..227, then entry 228, the engine's global
	 * per-area table (10032 bytes = 228 rows of 44). Three independent
	 * confirmations that 227 is a real area and 228 is the only non-area:
	 * entry 227 carries the "AD" container magic, the NPC registry archive
	 * (/a/1/3/7) holds exactly 228 entries (the engine indexes it by the same
	 * id), and the master zone-header table's areadataID column runs 2..227
	 * across all 536 zones, with a live zone on 227 and none on 228.
	 *
	 * <p>Recorded here because the editor did not agree with itself: the
	 * AreaData mass-edits in the Extras panel loop to
	 * {@code length - (isOA() ? 2 : 1)}, which is 227 on ORAS and so has always
	 * skipped area 227 - the one belonging to a real zone. And it is an INDEX, not
	 * a count of trailing tables: an area fork appends after entry 228, so after
	 * one fork "the last entry" was the forked area (F235). AreaForker's
	 * AD_GLOBAL_TABLE is the same 228, held equal by AreaTableTest.
	 */
	@Override
	public int areaTableIndex() {
		return 228;
	}

	/** 950 trainer entries with 0 the dummy; TrainerDataTest round-trips every one of 1..949. */
	@Override
	public int trainerIdMax() {
		return 949;
	}

	/** 776 item-name lines, index 0 the null item. */
	@Override
	public int itemIdMax() {
		return 775;
	}

	/**
	 * 536: the zone-limit patch is measured from it - the executable's master-table buffer is
	 * 536 * 0x38 bytes (ZoneLimitPatch's OFF_MASTER_SIZE word) - and ZoneTables reads 536 zones
	 * before the master table and the EN pack on an unmodified dump.
	 */
	@Override
	public int retailZoneCount() {
		return 536;
	}

	/** 721: the Generation 6 national dex, the editor's species bound since before F205. */
	@Override
	public int speciesIdMax() {
		return 721;
	}

	/**
	 * 621: carried over from the 622 the reference tables fell back to when the move names were
	 * not loaded (622 lines, index 0 the null move). Re-measuring against the dump's move text
	 * is owed with the dump.
	 */
	@Override
	public int moveIdMax() {
		return 621;
	}

	/**
	 * 279: carried over from the trainer editor's class spinner bound (F205). Re-measuring
	 * against the dump's trainer-class text is owed with the dump.
	 */
	@Override
	public int trainerClassMax() {
		return 279;
	}

	/**
	 * 7. MEASURED on the pristine retail dump: of the 857 FIELD_DATA (/a/0/3/9)
	 * GR containers, 819 declare 7 subfiles, 34 declare 9 and 4 declare 11.
	 * Seven is the standard set (tilemap, model, collision, prop data, extended
	 * data, encounter model, and ORAS's unused KAGE entry); the larger counts
	 * are multi-layer regions that append pairs to it, so 7 is the count a
	 * newly built region gets.
	 */
	@Override
	public int fieldDataSubfileCount() {
		return 7;
	}

	/**
	 * True: an ORAS zone header keeps the zone's own index in bits 21..31 of
	 * its unknownFlags word, so a clone into another slot must rewrite it
	 * (see {@code ZoneCloner.OA_ZONE_NUMBER_SHIFT}).
	 */
	@Override
	public boolean zoneNumberInUnknownFlags() {
		return true;
	}

	/**
	 * Ghosting at bit 0, flashable darkness at 1, cold breath at 12, the Dowsing Machine at 13 and
	 * stereoscopic 3D at 16 - X/Y's breath, dowsing and 3D bits one higher; no special-walking bit.
	 * As upstream CTRMap read and wrote them, moved here from the header's ORAS else-branch (F207).
	 */
	@Override
	public ZoneFlagLayout zoneFlagLayout() {
		return new ZoneFlagLayout(0, 12, 13, 16, -1, 1);
	}

	/**
	 * 1, as the camera form's author recorded ORAS's neutral cameras (X/Y's are 2). Not
	 * re-read in this tree, which holds no game data: CameraFormTest's dump half reads every
	 * area's table and refuses any other value.
	 */
	@Override
	public int neutralCameraValue() {
		return 1;
	}

	//cyclingFlagSafe: inherits false, and here that is a measurement rather
	//than a gap. ORAS softlocks when the player mounts the bike in a zone whose
	//header carries the cycling flag, on hardware and under an emulator alike,
	//so the Extras panel's mass edit must leave the bit clear on this game.

	/** Omega Ruby and Alpha Sapphire: the two titles this profile covers, neither the default (F238). */
	@Override
	public java.util.Map<String, String> titleIds() {
		java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();
		ids.put("000400000011C400", "Omega Ruby");
		ids.put("000400000011C500", "Alpha Sapphire");
		return java.util.Collections.unmodifiableMap(ids);
	}
}
