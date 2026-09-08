package ctrmap.formats.tilemap;

import ctrmap.formats.containers.GR;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import ctrmap.LittleEndianDataOutputStream;

/**
 * One region's 40x40 movement tiles, and the picture of them the tile editor
 * shows.
 *
 * <p>The picture's colours are HANDED in, as a {@link TileColors}. This used
 * to read the tileset off the application's tile form, so a region could only
 * be pictured with the application's colours, a region made with no window
 * silently had no picture, and no suite could hand it a palette of its own
 * and read the pixels back. {@code ctrmap.tests.HandedGameTest} hands it two.
 */
public class Tilemap {

	/**
	 * What the picture wants for each tile: a colour. Declared here, by the
	 * format layer; the editor's tileset supplies the real one and a suite a
	 * lambda.
	 */
	public interface TileColors {

		/** The colour a tile of this raw value is drawn in. */
		Color colorOf(int tile);
	}

	public GR mapFile;
	public byte[][][] rawTileData;
	public boolean modified;
	private BufferedImage tilemapImage;
	private Graphics g;
	private short width;
	private short height;
	/** Null when nobody wants a picture: the data is held without one. */
	private final TileColors colors;

	/** A region read from its container, pictured with the given colours (null for no picture). */
	public Tilemap(GR mapFile, TileColors colors) {
		this.mapFile = mapFile;
		this.colors = colors;
		rawTileData = getTileData();
		tilemapImage = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		g = tilemapImage.getGraphics();
		updateImage();
	}

	/** A region read from its container, with no picture: a headless holder of the data. */
	public Tilemap(GR mapFile) {
		this(mapFile, null);
	}

	/** A fresh region of unwalkable tiles, pictured with the given colours (null for no picture). */
	public Tilemap(GR mapFile, int width, int height, TileColors colors) {
		this.mapFile = mapFile;
		this.colors = colors;
		this.width = (short) width;
		this.height = (short) height;
		rawTileData = new byte[width][height][4];
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				rawTileData[x][y] = new byte[]{0x21, 0, 0, 1}; //fill with unwalkables
			}
		}
		tilemapImage = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
		g = tilemapImage.getGraphics();
		updateImage();
	}

	/** A fresh region of unwalkable tiles, with no picture. */
	public Tilemap(GR mapFile, int width, int height) {
		this(mapFile, width, height, null);
	}

	private byte[][][] getTileData() {
		try {
			InputStream in = new ByteArrayInputStream(mapFile.getFile(0));
			width = Short.reverseBytes((short) ((in.read() << 8) | in.read()));
			height = Short.reverseBytes((short) ((in.read() << 8) | in.read()));
			byte[][][] b = new byte[width][height][4];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					in.read(b[x][y]);
				}
			}
			in.close();
			return b;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	public byte[] assembleTilemap() {
		//GF likes their files to start at offsets ending with either 00 or 80
		//even though we can disrespect that and the games run just fine, it's prettier and easier for debugging
		//so we fill the remaining data till that offset with zeros. the tile data starts at 0x80 but we'll rather do it programatically.
		int startingOffset = mapFile.getOffset(0);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);
		try {
			dos.writeShort(width);
			dos.writeShort(height);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					dos.write(getTileData(x, y));
				}
			}
			dos.flush();
			dos.close();
			byte[] out = baos.toByteArray();
			//every retail tilemap carries a zero tail padding the subfile to 6528
			//bytes (0x80 alignment); whether the game tolerates a truncated one is
			//untested - preserve the original padded length
			byte[] orig = mapFile.getFile(0);
			if (orig != null && orig.length > out.length) {
				out = java.util.Arrays.copyOf(out, orig.length);
			}
			return out;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	/** Repaints the picture from the tiles, in the handed colours; nothing to paint with means no picture. */
	public void updateImage() {
		if (colors == null) {
			return;
		}
		for (int x = 0; x < 40; x++) {
			for (int y = 0; y < 40; y++) {
				g.setColor(colors.colorOf(ctrmap.util.Bytes.ba2int(rawTileData[x][y])));
				g.fillRect(x * 10, y * 10, 10, 10);
			}
		}
	}

	public BufferedImage getImage() {
		return tilemapImage;
	}

	public void setTileData(int x, int y, byte[] tiledata) {
		modified = true;
		rawTileData[x][y] = tiledata.clone();
	}

	public byte[] getTileData(int x, int y) {
		return rawTileData[x][y];
	}
}
