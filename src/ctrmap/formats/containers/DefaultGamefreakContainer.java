
package ctrmap.formats.containers;

import ctrmap.formats.GameFiles;
import java.io.File;

public class DefaultGamefreakContainer extends AbstractGamefreakContainer{
	private short magic;

	/** Opens a container of any magic that reports its writes to {@code files}. */
	public DefaultGamefreakContainer(File f, short magic, GameFiles files) {
		//the magic first, THEN the open that checks the file against it (F050: super(f, files)
		//verified against a field not yet assigned, so every such container was refused)
		super(files);
		this.magic = magic;
		openContainer(f);
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
