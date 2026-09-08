package ctrmap.gamedef;

import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;

/**
 * Pokemon Sun / Moon (Gen 7) - PLACEHOLDER. Gen 7 kept GARC/LZ11/text-cipher
 * infrastructure but replaced the model+animation formats (BCH -> GFModel /
 * GFMotion) and restructured the overworld, so the whole Gen 6 format layer
 * does not apply. Nothing here is measured yet: fill archive paths, text
 * indices and the detection probe from a Sun/Moon dump, then build the GFModel
 * layer before flipping any map-related Feature. See ARCHITECTURE.md.
 */
public class SmProfile extends GameProfile {

	@Override
	public GameType type() {
		return GameType.SM;
	}

	@Override
	public String displayName() {
		return "Sun / Moon";
	}

	@Override
	public String archivePath(ArchiveType t) {
		return null; // TODO measure from a Sun/Moon dump
	}

	@Override
	public int textIndex(TextIndex t) {
		return -1; // TODO measure from a Sun/Moon dump
	}

	@Override
	public boolean supports(Feature f) {
		return false;
	}

	@Override
	public String detectFile() {
		return null; // TODO: pick a probe file once a dump is available
	}

	//zoneDataTrailingEntries / areaDataTrailingEntries / fieldDataSubfileCount
	//are deliberately NOT overridden: the base class answers -1 for each, which
	//means "nobody has measured this", and every caller is required to refuse
	//in words rather than compute a count from another game's number. Fill them
	//in from a Sun/Moon dump, not from ORAS.
}
