package ctrmap.gamedef;

import ctrmap.Workspace.ArchiveType;
import ctrmap.Workspace.GameType;

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
			case TRAINER_CLASS_NAMES: return 21;
			case TRAINER_NAMES: return 22;
			default: return -1;
		}
	}

	@Override
	public boolean supports(Feature f) {
		return true; // the reference game - everything is built and corpus-tested here
	}

	@Override
	public String detectFile() {
		return "a/2/9/8"; // the last GARC of the ORAS romfs
	}
}
