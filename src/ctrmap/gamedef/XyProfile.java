package ctrmap.gamedef;

import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;

/**
 * Pokemon X / Y. Same engine generation as ORAS (Gen 6, BCH/H3D formats), so
 * the format layer is expected to carry over - but ONLY the archive paths below
 * (upstream CTRMap + pk3DS GARCReference_XY) are established. Everything else
 * awaits measurement against an X/Y dump: run the corpus test battery against
 * it, fill in the numbers, and flip Feature flags one by one as suites pass
 * (the porting recipe is in ARCHITECTURE.md).
 */
public class XyProfile extends GameProfile {

	@Override
	public GameType type() {
		return GameType.XY;
	}

	@Override
	public String displayName() {
		return "X / Y";
	}

	@Override
	public String archivePath(ArchiveType t) {
		switch (t) {
			case AREA_DATA: return "/a/0/1/3";
			case FIELD_DATA: return "/a/0/4/1";
			case MAP_MATRIX: return "/a/0/4/2";
			case GAMETEXT: return "/a/0/7/4";
			//storytext base GARC 080 + language offset 2 (English) - pk3DS GARCReference_XY
			case STORYTEXT: return "/a/0/8/2";
			case ZONE_DATA: return "/a/0/1/2";
			case BUILDING_MODELS: return "/a/0/2/4";
			case NPC_REGISTRIES: return "/a/1/4/9";
			case MOVE_MODELS: return "/a/0/2/1";
			//GARC 220 - pk3DS GARCReference_XY. NOT measured against an XY dump
			//here; it is a cited reference, which the profile contract allows so
			//long as it says so. Verify before anything writes through it.
			case ITEM_DATA: return "/a/2/2/0";
			case SOUND_BCSAR: return "/sound/xy_sound.bcsar";
			//TRAINER_*/MAISON_*/PERSONAL/MOVE_DATA: locations not yet verified
			//for XY - measure from a dump before adding (do NOT copy from pk3DS
			//blind; the editors also assume ORAS record layouts).
			default: return null;
		}
	}

	@Override
	public int textIndex(TextIndex t) {
		switch (t) {
			case LOCATION_NAMES:
				return 72; // established by upstream CTRMap
			default:
				return -1; // XY GameText entry order differs from ORAS - measure from a dump
		}
	}

	@Override
	public boolean supports(Feature f) {
		switch (f) {
			case H3D_MAPS:
				return true; // upstream CTRMap loaded XY maps (same BCH family)
			default:
				return false; // not yet corpus-verified on XY
		}
	}

	@Override
	public String detectFile() {
		return "a/2/7/0"; // the last GARC of the XY romfs
	}

	/**
	 * 1: XY's ZONE_DATA carries the master zone-header table as its last entry
	 * and no encounter pack behind it. Established by upstream CTRMap, which
	 * reads the master table at {@code length - 1} on XY and {@code length - 2}
	 * on ORAS; NOT measured against an XY dump here, which this profile is
	 * required to say (see the archive paths above).
	 */
	@Override
	public int zoneDataTrailingEntries() {
		return 1;
	}

	/**
	 * 1: the engine's global per-area table is the last AREA_DATA entry, at
	 * index 170 in upstream CTRMap's XY mass-edits. Established by upstream
	 * CTRMap, not measured against an XY dump here.
	 */
	@Override
	public int areaDataTrailingEntries() {
		return 1;
	}

	/**
	 * 6: XY region containers hold the six subfiles the GR class lists by
	 * default (tilemap, model, collision, prop data, extended data, encounter
	 * model) - ORAS added a seventh. Established by upstream CTRMap, not
	 * measured against an XY dump here.
	 */
	@Override
	public int fieldDataSubfileCount() {
		return 6;
	}

	/**
	 * True: X/Y rides a bike in any zone whose header allows it, which is the
	 * difference the Extras panel's mass edit used to spell as
	 * {@code enableCycling = Workspace.isXY()}. Established by upstream CTRMap,
	 * whose XY builds set the flag; the softlock that keeps it clear on ORAS
	 * does not happen here.
	 */
	@Override
	public boolean cyclingFlagSafe() {
		return true;
	}

	/**
	 * Pokemon X. Y is 0004000000055E00 and this profile covers both, which is
	 * why the deployer prefers the dumped folder's own name.
	 */
	@Override
	public String titleId() {
		return "0004000000055D00";
	}

	//zoneNumberInUnknownFlags: inherits false. Upstream CTRMap copies bits
	//21..31 of an XY zone header's unknownFlags verbatim when cloning, i.e. it
	//does not treat them as the zone index.
}
