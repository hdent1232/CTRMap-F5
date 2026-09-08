package ctrmap.formats.containers;

import ctrmap.formats.GameFiles;
import java.io.File;

/**
 * BuildModel container class.
 *
 * Contents: 0 - Model 1 to 65535 - BCH animations or CGFX particles
 */
public class BM extends AbstractGamefreakContainer {

	/** Opens a building-model container that reports its writes to {@code files}. */
	public BM(File f, GameFiles files) {
		super(f, files);
	}

	@Override
	public short getHeader() {
		return 0x424D;
	}

	@Override
	public ContentType getDefaultContentType(int index) {
		if (index == 0) {
			return ContentType.H3D_MODEL;

		} else {
			return ContentType.H3D_OTHER;
		}
	}

	@Override
	public boolean getIsPadded() {
		return true;
	}
}
