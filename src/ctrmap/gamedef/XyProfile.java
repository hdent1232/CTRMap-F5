package ctrmap.gamedef;

import ctrmap.Workspace.ArchiveType;
import ctrmap.Workspace.GameType;

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
}
