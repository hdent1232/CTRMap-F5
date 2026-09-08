
package ctrmap.formats.containers;

import ctrmap.formats.GameFiles;
import java.io.File;

public class DefaultGamefreakContainer extends AbstractGamefreakContainer{
	private short magic;

	/** Opens a container of any magic that reports its writes to {@code files}. */
	public DefaultGamefreakContainer(File f, short magic, GameFiles files) {
		super(f, files);
		this.magic = magic;
	}

	/** Transitional: see {@link AbstractGamefreakContainer}. */
	public DefaultGamefreakContainer(File f, short magic){
		super(f);
		this.magic = magic;
	}
	
	@Override
	public short getHeader() {
		return magic;
	}

	@Override
	public ContentType getDefaultContentType(int index) {
		return ContentType.UNKNOWN;
	}

	@Override
	public boolean getIsPadded() {
		return true;
	}
}
