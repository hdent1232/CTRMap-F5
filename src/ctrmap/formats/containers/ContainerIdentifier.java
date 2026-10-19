package ctrmap.formats.containers;

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
		if (ctrmap.util.Bytes.isUTF8Capital(magic[0]) && ctrmap.util.Bytes.isUTF8Capital(magic[1])) {
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

	/**
	 * Why {@code candidate} cannot replace the container at {@code current}, or null when it can: it must be a
	 * container, of the same kind. Asked BEFORE the copy (census H.40): the Builder copied the chosen file over the
	 * workspace's container first and identified it after, so a file that was no container - or a container of
	 * another kind - was kept and packed into the game.
	 */
	public static String whyNotReplaceable(File current, File candidate) {
		byte[] want = magic(current);
		byte[] got = magic(candidate);
		if (got == null || !ctrmap.util.Bytes.isUTF8Capital(got[0]) || !ctrmap.util.Bytes.isUTF8Capital(got[1])) {
			return candidate.getName() + " is not a container: it does not start with a container's two-letter magic.";
		}
		if (want != null && (want[0] != got[0] || want[1] != got[1])) {
			return candidate.getName() + " is a " + new String(got, java.nio.charset.StandardCharsets.US_ASCII)
					+ " container, and the one it would replace is a "
					+ new String(want, java.nio.charset.StandardCharsets.US_ASCII) + ".";
		}
		return null;
	}

	/** The first two bytes of {@code f}; null when it holds fewer or cannot be read. */
	private static byte[] magic(File f) {
		try (InputStream in = new FileInputStream(f)) {
			byte[] magic = new byte[2];
			return in.read(magic) == 2 ? magic : null;
		} catch (IOException unread) {
			return null;
		}
	}

	/** The container at {@code f}, its magic read from the file, reporting its writes to {@code files}. */
	public static AbstractGamefreakContainer makeAGFC(File f, GameFiles files) {
		try (InputStream in = new FileInputStream(f)) {
			byte[] magic = new byte[2];
			in.read(magic);
			return makeAGFC(f, magic, files);
		} catch (IOException ex) {
			Logger.getLogger(ContainerIdentifier.class.getName()).log(Level.SEVERE, null, ex);
			return null;
		}
	}

}
