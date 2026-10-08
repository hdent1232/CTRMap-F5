package ctrmap.formats.cameradata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import ctrmap.LittleEndianDataInputStream;
import ctrmap.LittleEndianDataOutputStream;
import java.util.Arrays;

/**
 * Camera data entry loader
 */
public class CameraData {

	public byte[] unknownBytes = new byte[16];
	public int acceptCoords2; //should 2nd coords be taken into consideration for transitions etc? Same as 07 in movDir but used a few times, changes the fov by a %;
	public int acceptCoords1; //same for 1
	public short transitionPeriod; //*35ms
	public int layer; //layer
	public int unknown00;
	public int isNeutral; //only used in the rock gym (gym03/cyllage) but has a clear purpose - when set to 02, it acts as a neutral zone where the camera angle remains
	//the same when approached from different sides. serves in corners to retain camera angle when walked on from multiple camera'd tiles. when set to 00 on a 02 cam,
	//the cam acts as normal with a 00 FOV and angles, causing the game to look (in case of Grant's gym) towards the waterfall.
	public int unknown01or03;

	public CameraCoordinates coords1;

	public short boundY1;
	public short boundY2;
	public short boundX1;
	public short boundX2;

	public int unknownInt1;

	public short movementDirection; //1 is horizontal, 2 is vertical, 7 is fixed

	public short isFirstEnabled; //0000 is yes, FFFF is no, comes first in file but second from top/left in order
	public short isSecondEnabled;

	public short unknownFFFF;

	public CameraCoordinates coords2;

	/**
	 * Reads one camera, or lets the failure out.
	 *
	 * <p>IT CAUGHT AND PRINTED. {@code dis.read()} answers -1 at the end of a stream
	 * rather than throwing, so a table that declared more cameras than it held handed
	 * back records made of -1s for as long as the count said, and the only sign was a
	 * stack trace on a console this program's users do not have. The editor then
	 * offered those records, and its Save wrote them back over the real table.
	 */
	public CameraData(LittleEndianDataInputStream dis) throws IOException {
		dis.read(unknownBytes);
		acceptCoords2 = dis.read();
		acceptCoords1 = dis.read();
		transitionPeriod = dis.readShort();
		layer = dis.readByte();
		unknown00 = dis.read();
		isNeutral = dis.read();
		unknown01or03 = dis.read();

		coords1 = new CameraCoordinates(dis);

		boundY1 = dis.readShort();
		boundY2 = dis.readShort();
		boundX1 = dis.readShort();
		boundX2 = dis.readShort();

		unknownInt1 = dis.readInt();

		movementDirection = dis.readShort(); //maybe it's short considering 00_3 is always 0, but why would it be?
		//always 1, 2, 7 except for weird cases - e4 rooms (0) another weird one is parfum palace with
		//an incomplete tilemap connected with weird stuff I'll probably never RE used for the grass gym etc.
		//the stuff with "track" "connect" "line@normal" "joint" and the like.

		isFirstEnabled = dis.readShort();
		isSecondEnabled = dis.readShort();

		unknownFFFF = dis.read2Bytes(); //also always FFFF except for e4 rooms and guess what, parfum palace

		coords2 = new CameraCoordinates(dis);
	}

	public CameraData() {
		unknownBytes = new byte[16];
		acceptCoords2 = 0x1;
		acceptCoords1 = 0x1;
		transitionPeriod = 0x0f;
		layer = 0xFF;
		isNeutral = 0x00;
		unknown01or03 = 0x01;

		coords1 = new CameraCoordinates();

		boundY1 = 0;
		boundY2 = 0;
		boundX1 = 0;
		boundX2 = 0;

		unknownInt1 = 0;

		movementDirection = 0x7;

		isFirstEnabled = 0;
		isSecondEnabled = 0;

		unknownFFFF = -1;

		coords2 = new CameraCoordinates();
	}

	/**
	 * The same record in the file: the bytes {@link #write} produces.
	 *
	 * <p>IT COMPARED 16 OF THE 19 FIELDS (F210), leaving out the three unknowns, so a change
	 * confined to them read as none; and with no hashCode beside it, two equal records hashed
	 * apart. Comparing what is written cannot leave a field out.
	 */
	@Override
	public boolean equals(Object o) {
		return o instanceof CameraData && Arrays.equals(bytes(), ((CameraData) o).bytes());
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes());
	}

	/** Each field's width in bytes, in the order {@link #write} puts them. */
	private static final int[] FIELDS = {16, 1, 1, 2, 1, 1, 1, 1, 4, 4, 4, 4, 4, 4, 4,
		2, 2, 2, 2, 4, 2, 2, 2, 2, 4, 4, 4, 4, 4, 4, 4};

	/** One record's size in the table: the widths above, summed. */
	public static final int SIZE = java.util.stream.IntStream.of(FIELDS).sum();

	/** The record as {@link #write} puts it in the table - what equality and {@link #keep} compare. */
	public byte[] bytes() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			write(new LittleEndianDataOutputStream(out));
		} catch (IOException cannotWrite) {
			throw new java.io.UncheckedIOException(cannotWrite);
		}
		return out.toByteArray();
	}

	/** An independent copy, its coordinates included: its own bytes read back. */
	public CameraData copy() {
		return of(bytes());
	}

	private static CameraData of(byte[] record) {
		try {
			return new CameraData(new LittleEndianDataInputStream(new ByteArrayInputStream(record)));
		} catch (IOException cannotRead) {
			throw new java.io.UncheckedIOException(cannotRead);
		}
	}

	/**
	 * The record a form writes: {@code raw} as it was read, with each field the form MOVED
	 * taken from {@code now}. A field moved when what the form says now differs from what it
	 * said when it showed {@code raw} - {@code shown} - and one it never moved is written
	 * back exactly as read.
	 *
	 * <p>WHY. The camera form built the record from its widgets, and several of them cannot
	 * hold what the file does: the FOV slider is whole degrees, the motion box has three rows
	 * for any number of codes, the neutral box is one tick over a byte, and no widget holds
	 * the unknowns. So the record it wrote had a stored 30.4 FOV as 30 the moment any other
	 * field changed (F123), had an unnamed motion code as 7 on an entry switch with no edit
	 * at all (F127), and had the two unknowns rewritten (F097).
	 *
	 * <p>BY FIELD, OVER THE SERIALISED LAYOUT: that reaches every field, one added later
	 * included, with no list of names to fall behind - and per field rather than per byte,
	 * because a float made of bytes from both sides is neither value.
	 *
	 * @throws IllegalStateException when a record is not {@link #SIZE} bytes, i.e. when
	 * {@link #write} and the layout above no longer agree
	 */
	public static CameraData keep(CameraData raw, CameraData shown, CameraData now) {
		byte[] r = raw.bytes();
		byte[] s = shown.bytes();
		byte[] n = now.bytes();
		if (r.length != SIZE || s.length != SIZE || n.length != SIZE) {
			throw new IllegalStateException("a camera record wrote " + r.length + "/" + s.length + "/"
				+ n.length + " bytes and its field layout says " + SIZE + ": write() and FIELDS disagree");
		}
		int at = 0;
		for (int width : FIELDS) {
			for (int i = at; i < at + width; i++) {
				if (s[i] != n[i]) {
					System.arraycopy(n, at, r, at, width);
					break;
				}
			}
			at += width;
		}
		return of(r);
	}

	public void write(LittleEndianDataOutputStream dos) throws IOException {
		try {
			dos.write(unknownBytes);
			dos.write(acceptCoords2);
			dos.write(acceptCoords1);
			dos.writeShort(transitionPeriod);
			dos.writeByte(layer);
			dos.write(unknown00);
			dos.write(isNeutral);
			dos.write(unknown01or03);

			coords1.write(dos);

			dos.writeShort(boundY1);
			dos.writeShort(boundY2);
			dos.writeShort(boundX1);
			dos.writeShort(boundX2);
			dos.writeInt(unknownInt1);
			dos.writeShort(movementDirection);
			dos.writeShort(isFirstEnabled);
			dos.writeShort(isSecondEnabled);
			dos.write2Bytes(unknownFFFF);

			coords2.write(dos);
		} catch (IOException cannotWrite) {
			//NOT LOGGED, for the reason in CameraCoordinates.write: a half-written record
			//becomes a short table, stored, and called saved.
			throw new java.io.IOException("camera data could not be written: "
				+ ctrmap.util.Bytes.reason(cannotWrite), cannotWrite);
		}
	}
}
