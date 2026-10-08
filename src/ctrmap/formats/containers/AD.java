package ctrmap.formats.containers;

import ctrmap.formats.GameFiles;
import java.io.File;

/**
 * Overworld AreaData container
 * 
 * Contents:
 * 0 - Prop registry
 * 1 - Prop textures
 * 2 - World animations
 * 3 - Camera data
 * 4 - Area environment: fog and lighting, 61 channels of 12 floats ({@link #ENVIRONMENT},
 *     read by AreaEnv, which decodes the fog channels 54-59 and copies the rest whole)
 * 5 - Environment assets (clouds, extra lighting)
 * 6 - Default camera data
 * 7 - Extended area collision
 * 8 - Map LOD assets
 * 9 - Global LOD assets
 * 10 - Unused in both XY and ORAS
 * 11 - World textures
 *
 * <p>5, 6, 8, 9 and 10 have no reader or editor anywhere in the tree: they are carried byte for byte
 * when an area is copied, and stay on the open reverse-engineering list (F221).
 */
public class AD extends AbstractGamefreakContainer{
	
	/** The subfile holding the area's environment block - fog and lighting (F236: it was listed as "???"). */
	public static final int ENVIRONMENT = 4;
	
	public ContentType[] contents = {
		ContentType.PROP_REGISTRY,
		ContentType.H3D_TEXTURE_PACK,
		ContentType.H3D_OTHER,
		ContentType.CAMERA_DATA,
		ContentType.AREA_ENVIRONMENT,
		ContentType.H3D_OTHER,
		ContentType.CAMERA_DATA,
		ContentType.COLLISION,
		ContentType.SUB_CONTAINER,
		ContentType.SUB_CONTAINER,
		ContentType.UNKNOWN,
		ContentType.H3D_TEXTURE_PACK
	};
	
	/** Opens an area container that reports its writes to {@code files}. */
	public AD(File f, GameFiles files) {
		super(f, files);
	}

	@Override
	public short getHeader() {
		return 0x4144;
	}

	@Override
	public ContentType getDefaultContentType(int index) {
		return contents[index];
	}

	@Override
	public boolean getIsPadded() {
		return true;
	}
}
