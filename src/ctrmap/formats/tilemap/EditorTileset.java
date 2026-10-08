package ctrmap.formats.tilemap;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A METS tileset: the names and colours the 2D tile editor draws a region's tiles with - and the
 * one reader and writer of the file, which the tileset editor (Extras) uses too.
 *
 * <p>THERE WERE TWO OF EACH (F026). The tileset editor parsed and wrote the format itself, beside
 * this reader: it wrote a name's length in CHARACTERS before its UTF-8 bytes, so one non-ASCII
 * name made a file neither reader could follow, and it logged a tile that failed after the count
 * was already written. This reader logged a file it could not open or read and handed back a
 * tileset with no tiles, or a half-read one with nulls in it (census M.9), and never closed the
 * file (F254).
 */
public class EditorTileset implements Tilemap.TileColors {

	public TileTemplate[] tiles;

	public static final int METS_MAGIC = 0x4D455453;

	/** The fewest bytes a tile takes: a zero-length name, two ints and two category bytes. */
	private static final int SMALLEST_TILE = 1 + 4 + 4 + 1 + 1;

	/** One tile as the file holds it, every field as stored - what the tileset editor edits. */
	public static final class Record {

		public int binary;
		public String name;
		public int color;
		public int cat1;
		public int cat2;

		public Record(int binary, String name, int color, int cat1, int cat2) {
			this.binary = binary;
			this.name = name;
			this.color = color;
			this.cat1 = cat1;
			this.cat2 = cat2;
		}
	}

	/** Reads a tileset file whole, or refuses it. */
	public EditorTileset(File f) throws IOException {
		this(read(Files.readAllBytes(f.toPath())));
	}

	/** Reads a tileset from a stream, which it closes whatever happens, or refuses it. */
	public EditorTileset(InputStream in) throws IOException {
		this(read(readAll(in)));
	}

	private EditorTileset(List<Record> records) {
		tiles = new TileTemplate[records.size()];
		for (int i = 0; i < tiles.length; i++) {
			Record r = records.get(i);
			tiles[i] = new TileTemplate(r.binary, r.color, r.name, r.cat1, r.cat2);
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		if (in == null) {
			throw new IOException("there is no tileset to read (the stream is missing)");
		}
		try (InputStream closing = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = closing.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	/**
	 * The tiles a METS file holds, or a refusal naming what is wrong: the magic, a count the
	 * bytes cannot hold, a tile cut short, a name that is not UTF-8, or bytes after the last
	 * tile. Every byte is accounted for, so what reads is what {@link #write} gives back.
	 */
	public static List<Record> read(byte[] file) throws IOException {
		if (file.length < 8) {
			throw new IOException("a tileset starts with 8 bytes of header, and this is " + file.length + " bytes");
		}
		DataInputStream dis = new DataInputStream(new ByteArrayInputStream(file));
		int magic = dis.readInt();
		if (magic != METS_MAGIC) {
			throw new IOException("this is not a tileset: it does not start with METS");
		}
		int count = dis.readInt();
		if (count < 0 || count > (file.length - 8) / SMALLEST_TILE) {
			throw new IOException("it declares " + count + " tiles in " + file.length + " bytes, which cannot be right");
		}
		List<Record> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			try {
				byte[] name = new byte[dis.readUnsignedByte()];
				dis.readFully(name);
				String text;
				try {
					text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
							.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
				} catch (CharacterCodingException notUtf8) {
					throw new IOException("tile " + i + "'s name is not UTF-8 (a file whose name lengths were"
							+ " written in characters reads this way)");
				}
				int binary = dis.readInt();
				int color = dis.readInt();
				int cat1 = dis.readUnsignedByte();
				int cat2 = dis.readUnsignedByte();
				out.add(new Record(binary, text, color, cat1, cat2));
			} catch (java.io.EOFException cutShort) {
				throw new IOException("the file ends inside tile " + i + " of the " + count + " it declares");
			}
		}
		if (dis.available() > 0) {
			throw new IOException(dis.available() + " bytes follow the last of the " + count + " tiles");
		}
		return out;
	}

	/**
	 * The file these tiles make, or a refusal before anything is produced: a name is at most 255
	 * bytes of UTF-8, because its length is one byte.
	 */
	public static byte[] write(List<Record> tiles) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(out);
		dos.writeInt(METS_MAGIC);
		dos.writeInt(tiles.size());
		for (int i = 0; i < tiles.size(); i++) {
			Record t = tiles.get(i);
			byte[] name = t.name.getBytes(StandardCharsets.UTF_8);
			if (name.length > 255) {
				throw new IOException("tile " + i + "'s name is " + name.length + " bytes of UTF-8, and a tileset"
						+ " holds at most 255");
			}
			dos.writeByte(name.length);
			dos.write(name);
			dos.writeInt(t.binary);
			dos.writeInt(t.color);
			dos.writeByte(t.cat1);
			dos.writeByte(t.cat2);
		}
		return out.toByteArray();
	}

	public TileTemplate getTemplate(int binary) {
		for (int i = 0; i < tiles.length; i++) {
			if (tiles[i].binary == binary) {
				return tiles[i];
			}
		}
		return new TileTemplate(binary, 0xFF0000, "Unknown", 0, 0);
	}

	public TileTemplate getTemplate(int cat1, int cat2, String name) {
		for (int i = 0; i < tiles.length; i++) {
			if (tiles[i].name.equals(name) && tiles[i].cat1 == cat1 && tiles[i].cat2 == cat2) {
				return tiles[i];
			}
		}
		return null;
	}

	/** The colour a region's picture draws a tile in: {@link #getSimpleColor}. */
	@Override
	public Color colorOf(int tile) {
		return getSimpleColor(tile);
	}

	public Color getSimpleColor(int binary){
		int imperfectMatch = -1;
		for (int i = 0; i < tiles.length; i++) {
			if ((tiles[i].binary & 0xFF) == (binary & 0xFF)) {
				if (tiles[i].binary == binary){
					return tiles[i].color;
				}
				else {
					imperfectMatch = i;
				}
			}
		}
		if (imperfectMatch != -1){
			return tiles[imperfectMatch].color;
		}
		return Color.RED;
	}
}
