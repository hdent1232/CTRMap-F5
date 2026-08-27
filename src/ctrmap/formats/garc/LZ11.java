package ctrmap.formats.garc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * LZ11 decompression ported from C# source of PK3DS, originally from dsdecmp
 * LZ11 compression ported from Ohana3DS's VB.NET using https://www.carlosag.net/tools/codetranslator/ for syntax and some intuition to make it work from Ohana3DS (non-Rebirth)
 * 
 */
public class LZ11 {

	public static byte[] decompress(byte[] data) {
		ByteArrayInputStream instream = new ByteArrayInputStream(data);
		ByteArrayOutputStream outstream = new ByteArrayOutputStream();

		byte type = (byte) instream.read();
		if (type != 0x11) {
			System.err.println("Not a LZ11 compressed file");
		}
		byte[] sizeBytes = new byte[4];
		instream.read(sizeBytes, 0, 3);
		int decompressedSize = Integer.reverseBytes(ByteBuffer.wrap(sizeBytes).getInt());

		final int bufferLength = 0x1000;
		byte[] buffer = new byte[bufferLength];
		int bufferOffset = 0;

		int currentOutSize = 0;
		int flags = 0, mask = 1;
		while (currentOutSize < decompressedSize) {
			if (mask == 1) {
				flags = instream.read();
				mask = 0x80;
			} else {
				mask >>= 1;
			}
			if ((flags & mask) > 0) {
				int byte1 = instream.read();
				int length = byte1 >> 4;
				int disp;

				switch (length) {
					case 0: {
						int byte2 = instream.read();
						int byte3 = instream.read();
						length = (((byte1 & 0x0F) << 4) | (byte2 >> 4)) + 0x11;
						disp = (((byte2 & 0x0F) << 8) | byte3) + 0x1;
						break;
					}
					case 1: {
						int byte2 = instream.read();
						int byte3 = instream.read();
						int byte4 = instream.read();
						length = (((byte1 & 0x0F) << 12) | (byte2 << 4) | (byte3 >> 4)) + 0x111;
						disp = (((byte3 & 0x0F) << 8) | byte4) + 0x1;
						break;
					}
					default: {
						int byte2 = instream.read();
						length = ((byte1 & 0xF0) >> 4) + 0x1;
						disp = (((byte1 & 0x0F) << 8) | byte2) + 0x1;
						break;
					}
				}

				int bufIdx = bufferOffset + bufferLength - disp;
				for (int i = 0; i < length; i++) {
					byte next = buffer[bufIdx % bufferLength];
					bufIdx++;
					outstream.write(next);
					buffer[bufferOffset] = next;
					bufferOffset = (bufferOffset + 1) % bufferLength;
				}
				currentOutSize += length;
			} else {
				int next = instream.read();

				outstream.write((byte) next);
				currentOutSize++;
				buffer[bufferOffset] = (byte) next;
				bufferOffset = (bufferOffset + 1) % bufferLength;
			}
		}
		return outstream.toByteArray();
	}

	// LZ11 window + match limits (must match the token forms the decoder reads)
	private static final int WINDOW = 0x1000;       // max displacement 4096
	private static final int MIN_MATCH = 3;
	private static final int MAX_MATCH = 0xFFFF + 0x111; // 65808, the 4-byte form ceiling
	private static final int HASH_BITS = 15;
	private static final int HASH_SIZE = 1 << HASH_BITS;
	private static final int MAX_CHAIN = 128;        // longest-match search depth (ratio vs speed)

	/** Growable byte buffer with O(1) indexed patch (for rewriting flag bytes in place). */
	private static final class Buf {

		byte[] a = new byte[256];
		int len = 0;

		void w(int b) {
			if (len == a.length) {
				a = java.util.Arrays.copyOf(a, a.length << 1);
			}
			a[len++] = (byte) b;
		}

		void set(int i, int v) {
			a[i] = (byte) v;
		}

		byte[] toArray() {
			return java.util.Arrays.copyOf(a, len);
		}
	}

	/**
	 * Greedy longest-match LZ11 encoder with hash-chained match finding. Emits
	 * all three GF token forms (2/3/4-byte, for match lengths 3..16 / 17..272 /
	 * 273..65808), so long runs cost a few bytes instead of one token per 15
	 * bytes like the previous port - output is a fraction of the size and still
	 * decodes byte-identically. Every back-reference stays within the 4096-byte
	 * window and never references data ahead of the cursor.
	 */
	public static byte[] compress(byte[] data) {
		int n = data.length;
		Buf out = new Buf();
		out.w(0x11);
		out.w(n & 0xFF);
		out.w((n >> 8) & 0xFF);
		out.w((n >> 16) & 0xFF);

		// hash chains: head[hash] = last position with that 3-byte hash; prev[p] links back
		int[] head = new int[HASH_SIZE];
		int[] prev = new int[n < 1 ? 1 : n];
		java.util.Arrays.fill(head, -1);

		int i = 0;
		int flagPos = -1;
		int flagBit = 0; // bits still free in the current flag byte
		int flags = 0;
		while (i < n) {
			long m = findMatch(data, i, n, head, prev);
			int bestLen = (int) (m >>> 32);
			int bestDist = (int) m;

			if (flagBit == 0) {
				flagPos = out.len;
				out.w(0);
				flags = 0;
				flagBit = 8;
			}
			flagBit--;
			if (bestLen >= MIN_MATCH) {
				flags |= 1 << flagBit;
				writeMatch(out, bestLen, bestDist);
				for (int k = 0; k < bestLen && i + k < n; k++) {
					insert(data, i + k, head, prev);
				}
				i += bestLen;
			} else {
				out.w(data[i] & 0xFF);
				insert(data, i, head, prev);
				i++;
			}
			out.set(flagPos, flags);
		}
		return out.toArray();
	}

	/** Longest match for position i, packed as (len<<32)|dist; len<MIN_MATCH if none. */
	private static long findMatch(byte[] data, int i, int n, int[] head, int[] prev) {
		if (i + MIN_MATCH > n) {
			return 0;
		}
		int hash = hash3(data, i);
		int cand = head[hash];
		int limit = Math.max(0, i - WINDOW);
		int bestLen = MIN_MATCH - 1, bestDist = 0;
		int maxLen = Math.min(MAX_MATCH, n - i);
		int chain = MAX_CHAIN;
		while (cand >= limit && cand >= 0 && chain-- > 0) {
			if (data[cand + bestLen] == data[i + bestLen]) { // quick reject on the frontier byte
				int len = 0;
				while (len < maxLen && data[cand + len] == data[i + len]) {
					len++;
				}
				if (len > bestLen) {
					bestLen = len;
					bestDist = i - cand;
					if (len >= maxLen) {
						break;
					}
				}
			}
			cand = prev[cand];
		}
		return bestLen >= MIN_MATCH ? (((long) bestLen) << 32) | (bestDist & 0xFFFFFFFFL) : 0;
	}

	private static void insert(byte[] data, int i, int[] head, int[] prev) {
		if (i + MIN_MATCH > data.length) {
			return;
		}
		int hash = hash3(data, i);
		prev[i] = head[hash];
		head[hash] = i;
	}

	private static int hash3(byte[] d, int i) {
		int h = (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16);
		h = (h * 0x9E3779B1) >>> (32 - HASH_BITS);
		return h & (HASH_SIZE - 1);
	}

	/** Writes one match token in the appropriate GF form. */
	private static void writeMatch(Buf out, int length, int dist) {
		int d = dist - 1; // stored displacement is disp-1 (0..4095)
		if (length <= 0x10) { // 2-byte form: len 3..16
			int l = length - 1;
			out.w((l << 4) | ((d >> 8) & 0x0F));
			out.w(d & 0xFF);
		} else if (length <= 0x110) { // 3-byte form: len 0x11..0x110
			int l = length - 0x11;
			out.w((l >> 4) & 0x0F);              // top nibble 0 selects this form
			out.w(((l & 0x0F) << 4) | ((d >> 8) & 0x0F));
			out.w(d & 0xFF);
		} else { // 4-byte form: len 0x111..0x10110
			int l = length - 0x111;
			out.w(0x10 | ((l >> 12) & 0x0F));    // top nibble 1 selects this form
			out.w((l >> 4) & 0xFF);
			out.w(((l & 0x0F) << 4) | ((d >> 8) & 0x0F));
			out.w(d & 0xFF);
		}
	}
}
