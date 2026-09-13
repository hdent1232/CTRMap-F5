package ctrmap;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Simple DataInputStream implementation with automatic reversion of LE to Java BE.
 */
public class LittleEndianDataInputStream {
	private DataInputStream dis;
	public LittleEndianDataInputStream(InputStream in) {
		dis = new DataInputStream(in);
	}
	
	public int readInt() throws IOException{
		return Integer.reverseBytes(dis.readInt());
	}
	
	public int read4Bytes() throws IOException{
		return dis.readInt();
	}
	
	public short readShort() throws IOException{
		return Short.reverseBytes(dis.readShort());
	}
	
	public int readUnsignedShort() throws IOException{
		return Short.toUnsignedInt(readShort());
	}
	
	public short read2Bytes() throws IOException{
		return dis.readShort();
	}
	
	public byte readByte() throws IOException{
		return dis.readByte();
	}
	
	public int read() throws IOException{
		return dis.read();
	}
	
	public int read(byte[] b) throws IOException{
		return dis.read(b);
	}

	/**
	 * Fills {@code b} completely, or throws.
	 *
	 * <p>THE ONE THAT SHOULD BE USED for reading a record of known length.
	 * {@link #read(byte[])} answers how many bytes it managed, and the tree is full of
	 * callers that dropped that answer - a file shorter than its own table then handed
	 * back a buffer of the right length whose tail was zeros, and reported success.
	 * Measured on a GARC truncated to half its size: 278 of 431 entries came back pure
	 * zero, none null, no exception. This cannot do that: short data is an EOFException
	 * naming nothing, so the caller is the one that says which file it was reading.
	 */
	public void readFully(byte[] b) throws IOException{
		dis.readFully(b);
	}

	/** Skips exactly {@code n} bytes, or throws: skip() may do less and says so. */
	public void skipFully(long n) throws IOException{
		long done = 0;
		while (done < n) {
			long step = dis.skip(n - done);
			if (step <= 0) {
				if (dis.read() < 0) {
					throw new java.io.EOFException("wanted to skip " + n + " byte(s), reached the end"
						+ " after " + done);
				}
				done++;
			} else {
				done += step;
			}
		}
	}
	
	public float readFloat() throws IOException{
		return Float.intBitsToFloat(readInt());
	}
	
	public int available() throws IOException{
		return dis.available();
	}
	
	public long skip(long n) throws IOException{
		return dis.skip(n);
	}
	
	public void close() throws IOException{
		dis.close();
	}
}
