package ctrmap.formats.containers;

import ctrmap.Utils;
import ctrmap.formats.GameFiles;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens a container of whichever kind its two-byte magic says it is.
 *
 * <p>Handed the {@link GameFiles} the container will report its writes to,
 * because a container cannot be made without one: the constructors that
 * fetched the open game from the application's global themselves are gone
 * (see {@link AbstractGamefreakContainer}), and this class, which only makes
 * containers, would otherwise have been the one place in the format layer
 * still reaching for it.
 */
public class ContainerIdentifier {

	/** The container at {@code f} whose magic is {@code magic}, reporting its writes to {@code files}; null when the magic is not a container's. */
	public static AbstractGamefreakContainer makeAGFC(File f, byte[] magic, GameFiles files) {
		if (Utils.isUTF8Capital(magic[0]) && Utils.isUTF8Capital(magic[1])) {
			String magicStr = new String(new byte[]{magic[0], magic[1]});
			switch (magicStr) {
				case "AD":
					return new AD(f, files);
				case "BM":
					return new BM(f, files);
				case "GR":
					return new GR(f, files);
				case "MM":
					return new MM(f, files);
				case "ZO":
					return new ZO(f, files);
				default:
					return new DefaultGamefreakContainer(f, ByteBuffer.wrap(magic).getShort(), files);
			}
		} else {
			return null;
		}
	}

	/** The container at {@code f}, its magic read from the file, reporting its writes to {@code files}. */
	public static AbstractGamefreakContainer makeAGFC(File f, GameFiles files) {
		try {
			InputStream in = new FileInputStream(f);
			byte[] magic = new byte[2];
			in.read(magic);
			in.close();
			return makeAGFC(f, magic, files);
		} catch (IOException ex) {
			Logger.getLogger(ContainerIdentifier.class.getName()).log(Level.SEVERE, null, ex);
			return null;
		}
	}

}
