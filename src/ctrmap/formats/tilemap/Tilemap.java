package ctrmap.formats.tilemap;

import ctrmap.formats.containers.GR;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import ctrmap.util.LittleEndianDataOutputStream;
import ctrmap.util.LittleEndian;

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
	 * Bytes in one region's tilemap subfile (F117): a 4-byte header and 40 x 40 tiles of 4 bytes, 6404,
	 * padded to 0x80 as every retail tilemap is. It was written out as 6528 in four builders.
	 */
	public static final int REGION_BYTES = 6528;

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

	/**
	 * The tiles, every one read whole.
	 *
	 * @throws IllegalStateException when the subfile is shorter than its own size says (census M.27): each short
	 * read was dropped, so the missing tiles became {0,0,0,0} and a save wrote those over the real movement data.
	 * Measured 2026-10-12: all 857 regions of the ORAS dump hold a whole tilemap, so no region the game ships is
	 * refused.
	 */
	private byte[][][] getTileData() {
		byte[] file = mapFile.getFile(0);
		if (file.length < 4) {
			throw new IllegalStateException("This region's tilemap (subfile 0) is "
					+ "only " + file.length + " bytes" + ", so it is not opened and nothing"
					+ " will be saved over it.");
		}
		width = (short) LittleEndian.u16(file, 0);
		height = (short) LittleEndian.u16(file, 2);
		int needed = 4 + width * height * 4;
		if (file.length < needed) {
			throw new IllegalStateException("This region's tilemap (subfile 0) holds " + file.length + " bytes, and a "
					+ width + " by " + height + " tilemap needs " + needed + ", so it is not opened and nothing will be"
					+ " saved over it.");
		}
		byte[][][] b = new byte[width][height][4];
		int at = 4;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				System.arraycopy(file, at, b[x][y], 0, 4);
				at += 4;
			}
		}
		return b;
	}

	public byte[] assembleTilemap() {
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
			if (orig.length > out.length) {
				out = java.util.Arrays.copyOf(out, orig.length);
			}
			return out;
		} catch (IOException e) {
			//THROWN, NOT NULL (census L.2): the null went into storeFile on every tilemap save
			throw new IllegalStateException("the tilemap could not be assembled: " + ctrmap.util.Bytes.reason(e), e);
		}
	}

	/**
	 * Repaints the whole picture from the tiles, in the handed colours - for colours that changed; nothing to paint
	 * with means no picture. An edit does not need it: {@link #setTileData} paints the tile it changed (census L.39).
	 */
	public void updateImage() {
		for (int x = 0; x < 40; x++) {
			for (int y = 0; y < 40; y++) {
				paintTile(x, y);
			}
		}
	}

	/** Paints one tile of the picture in the handed colours; nothing to paint with means no picture. */
	private void paintTile(int x, int y) {
		if (colors == null) {
			return;
		}
		g.setColor(colors.colorOf(ctrmap.util.Bytes.ba2int(rawTileData[x][y])));
		g.fillRect(x * 10, y * 10, 10, 10);
	}

	/**
	 * Puts the tiles back to what the region holds and marks them unchanged (F106). A discard used to
	 * clear the flag alone, so the discarded tiles stayed drawn, and the next edit in the region wrote
	 * them along with it.
	 */
	public void discard() {
		rawTileData = getTileData();
		modified = false;
		updateImage();
	}

	public BufferedImage getImage() {
		return tilemapImage;
	}

	/**
	 * Sets one tile and paints it. ITS OWN TILE (census L.39): the Set tool repainted all 1,600 tiles, each through
	 * a scan of the tileset, on every mouse-drag event, for the one it changed.
	 */
	public void setTileData(int x, int y, byte[] tiledata) {
		modified = true;
		rawTileData[x][y] = tiledata.clone();
		paintTile(x, y);
	}

	public byte[] getTileData(int x, int y) {
		return rawTileData[x][y];
	}
}
