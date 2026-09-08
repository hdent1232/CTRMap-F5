package ctrmap.formats.containers;

import ctrmap.formats.GameFiles;
import java.io.File;

/**
 * GR (Game Region? as coined by Kaphotics somewhere) container class.
 * 
 * Contents:
 * 0 - Tilemap
 * 1 - Model
 * 2 - Collision mesh
 * 3 - Prop placement data.
 * 4 - Extended GR data.
 * 5 - Encounter model
 * [ORAS] - KAGE description, likely used for dynamic shadows or something (hence the name) 
 * but was scrapped early and is not-000000 only in the intro truck, where zeroing it changes nothing.
 */
public class GR extends AbstractGamefreakContainer{
	public ContentType[] contents = new ContentType[]{
		ContentType.TILEMAP,
		ContentType.H3D_MODEL,
		ContentType.COLLISION,
		ContentType.PROP_DATA,
		ContentType.UNKNOWN,
		ContentType.H3D_MODEL
	};
	
	/** Opens a region container that reports its writes to {@code files}. */
	public GR(File f, GameFiles files) {
		super(f, files);
	}

	/** Creates an empty region container of {@code len} slots that reports its writes to {@code files}. */
	public GR(File f, int len, GameFiles files) {
		super(f, len, files);
	}

	@Override
	public short getHeader() {
		return 0x4752;
	}

	@Override
	public ContentType getDefaultContentType(int index) {
		if (index > 5){
			//multi layer GR
			//can be tilemap or coll
			return ContentType.UNKNOWN;
		}
		else {
			return contents[index];
		}
	}

	@Override
	public boolean getIsPadded() {
		return true;
	}
}
