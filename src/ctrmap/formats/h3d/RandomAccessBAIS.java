package ctrmap.formats.h3d;

import ctrmap.util.LittleEndian;
import java.io.EOFException;
import java.io.IOException;

/**
 * A cursor over a BCH's bytes: little-endian reads at a position that can be moved.
 *
 * <p>THE POSITION IS THE INDEX (F228). This was a LittleEndianDataInputStream over a
 * ByteArrayInputStream that kept a count of its own beside the stream's, by overriding nine of its
 * read methods. The two drifted: skip added what it was asked for whatever the stream skipped (100
 * on six bytes said 100), read() at the end added one, read(byte[]) at the end added its -1 and went
 * backwards, a seek before the start said -2 while the stream read from 0, and readFully, skipFully
 * and read(byte[], int, int) were never overridden at all. Here every read takes its bytes from the
 * array at {@link #position()} through {@link LittleEndian} and moves it by what it took, so there is
 * no second count to forget. BchSectionsTest holds each case.
 *
 * <p>A read that runs past the end throws EOFException, as the stream's did; read() answers -1 there, as
 * a stream's does, and does not move.
 */
public class RandomAccessBAIS {

	private final byte[] data;
	private int position = 0;

	/** Reads {@code data} itself, not a copy: a write to the array is seen by the next read of it. */
	public RandomAccessBAIS(byte[] data) {
		this.data = data;
	}

	/** Where the next read starts. */
	public int position() {
		return position;
	}

	/** The index {@code n} bytes are read from, moving past them; or EOFException, not moving. */
	private int take(int n) throws EOFException {
		if (n > data.length - position) {
			throw new EOFException(n + " byte(s) at " + position + " run past the end of the data (" + data.length
					+ " bytes)");
		}
		int at = position;
		position += n;
		return at;
	}

	public int readInt() throws IOException {
		return LittleEndian.i32(data, take(4));
	}

	public short readShort() throws IOException {
		return (short) LittleEndian.u16(data, take(2));
	}

	public int readUnsignedShort() throws IOException {
		return LittleEndian.u16(data, take(2));
	}

	public byte readByte() throws IOException {
		return data[take(1)];
	}

	public float readFloat() throws IOException {
		return LittleEndian.f32(data, take(4));
	}

	/** The next byte, 0 to 255; or -1 at the end of the data, where the position stays. */
	public int read() throws IOException {
		return position < data.length ? data[position++] & 0xFF : -1;
	}

	/** All of {@code b}, moving past it; or EOFException naming where, not moving - never part of it (census M.110). */
	public void readFully(byte[] b) throws IOException {
		System.arraycopy(data, take(b.length), b, 0, b.length);
	}

	/** Moves forward {@code n} bytes, or to the end of the data if that comes first; answers how far it moved. */
	public long skip(long n) throws IOException {
		long k = Math.max(0, Math.min(n, (long) data.length - position));
		position += (int) k;
		return k;
	}

	/**
	 * Moves to {@code offset}. Past the end is allowed, as a file's seek allows it - the next read
	 * there answers the end - but before the start there is nothing to read, and it is refused.
	 */
	public void seek(int offset) throws IOException {
		if (offset < 0) {
			throw new IOException("seek to " + offset + ": before the start of the data");
		}
		position = offset;
	}
}
