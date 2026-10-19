package ctrmap.formats.garc;

import java.io.ByteArrayOutputStream;
import ctrmap.util.LittleEndian;

/**
 * LZ11 decompression ported from C# source of PK3DS, originally from dsdecmp
 * LZ11 compression ported from Ohana3DS's VB.NET using https://www.carlosag.net/tools/codetranslator/ for syntax and some intuition to make it work from Ohana3DS (non-Rebirth)
 * 
 */
public class LZ11 {

	/**
	 * Decodes an LZ11 stream, STRICTLY (F057). It used to decode anything: a wrong type byte was a
	 * line on stderr, input that ran out read as -1 - a flag byte with every bit set and runs at
	 * displacement 0 - and the loop padded its way to the declared length, so a truncated or damaged
	 * entry in the game's archives opened in the editor as garbage of a plausible length, and could
	 * be saved back. {@link #prefixDecodes} already knew the rules; this applies them to the whole
	 * stream. A well-formed stream decodes to exactly the bytes it always did.
	 *
	 * <p>A three-byte size of zero is the extended header: the size follows in four bytes. A bare
	 * four-byte header of size zero is an empty stream, which is what {@link #compress} writes for
	 * no input.
	 *
	 * @throws IllegalStateException naming what is wrong, for anything that is not a well-formed stream
	 */
	public static byte[] decompress(byte[] data) {
		if (data == null || data.length < 4) {
			throw new IllegalStateException("Not an LZ11 stream: it needs a 4-byte header and is "
					+ (data == null ? "absent" : data.length + " byte(s)") + ".");
		}
		if ((data[0] & 0xFF) != 0x11) {
			throw new IllegalStateException("Not an LZ11 stream: its type byte is 0x"
					+ Integer.toHexString(data[0] & 0xFF) + ", not 0x11.");
		}
		int decompressedSize = LittleEndian.u24(data, 1);
		int in = 4;
		if (decompressedSize == 0 && data.length > 4) {
			if (data.length < 8) {
				throw new IllegalStateException("An LZ11 stream with an extended size header needs 8 header bytes;"
						+ " this is " + data.length + ".");
			}
			decompressedSize = LittleEndian.i32(data, 4);
			if (decompressedSize < 0) {
				throw new IllegalStateException("An LZ11 stream declares " + Integer.toUnsignedString(decompressedSize)
						+ " bytes, more than this program can hold.");
			}
			in = 8;
		}
		//GROWN, NOT PRE-SIZED (F240): the declared size is a header field, and handing it to the
		//buffer allocated whatever a damaged header said - 8 bytes declaring 2^31-1 were an
		//OutOfMemoryError before a byte was read. Output now grows with what is decoded, and a
		//stream that runs out says so (next) long before a false size could cost anything
		ByteArrayOutputStream outstream = new ByteArrayOutputStream(Math.min(decompressedSize, 1 << 16));

		final int bufferLength = 0x1000;
		byte[] buffer = new byte[bufferLength];
		int bufferOffset = 0;

		int currentOutSize = 0;
		int flags = 0, mask = 1;
		while (currentOutSize < decompressedSize) {
			if (mask == 1) {
				flags = next(data, in++, currentOutSize, decompressedSize);
				mask = 0x80;
			} else {
				mask >>= 1;
			}
			if ((flags & mask) > 0) {
				int byte1 = next(data, in++, currentOutSize, decompressedSize);
				int length = byte1 >> 4;
				int disp;

				switch (length) {
					case 0: {
						int byte2 = next(data, in++, currentOutSize, decompressedSize);
						int byte3 = next(data, in++, currentOutSize, decompressedSize);
						length = (((byte1 & 0x0F) << 4) | (byte2 >> 4)) + 0x11;
						disp = (((byte2 & 0x0F) << 8) | byte3) + 0x1;
						break;
					}
					case 1: {
						int byte2 = next(data, in++, currentOutSize, decompressedSize);
						int byte3 = next(data, in++, currentOutSize, decompressedSize);
						int byte4 = next(data, in++, currentOutSize, decompressedSize);
						length = (((byte1 & 0x0F) << 12) | (byte2 << 4) | (byte3 >> 4)) + 0x111;
						disp = (((byte3 & 0x0F) << 8) | byte4) + 0x1;
						break;
					}
					default: {
						int byte2 = next(data, in++, currentOutSize, decompressedSize);
						length = ((byte1 & 0xF0) >> 4) + 0x1;
						disp = (((byte1 & 0x0F) << 8) | byte2) + 0x1;
						break;
					}
				}
				if (disp > currentOutSize) {
					throw new IllegalStateException("The LZ11 stream refers " + disp + " byte(s) back when only "
							+ currentOutSize + " have been written - it is damaged, or not LZ11 at all.");
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
				int next = next(data, in++, currentOutSize, decompressedSize);

				outstream.write((byte) next);
				currentOutSize++;
				buffer[bufferOffset] = (byte) next;
				bufferOffset = (bufferOffset + 1) % bufferLength;
			}
		}
		return outstream.toByteArray();
	}

	/** The byte at {@code at}, or a refusal saying the stream ran out before its declared size. */
	private static int next(byte[] data, int at, int written, int declared) {
		if (at >= data.length) {
			throw new IllegalStateException("The LZ11 stream ends after " + written + " of the " + declared
					+ " bytes it declares - it is truncated or damaged.");
		}
		return data[at] & 0xFF;
	}

	/**
	 * The fewest stored bytes an LZ11 stream can have and still produce
	 * {@code declared} bytes of output: the 4-byte header, one 4-byte token
	 * per 65,808 output bytes (the longest run the format can encode), and one
	 * flag byte per 8 tokens. Arithmetic about the format, not a heuristic: an
	 * entry shorter than this cannot be a stream of that length whatever its
	 * header says. It is what refuses trclass a/0/3/7 #122 - 16 stored bytes
	 * declaring 16,720,640, which would need at least 1,056 - without a size
	 * ceiling that also refused two genuine 4.7 MB and 5.5 MB entries.
	 */
	public static int minimumStoredLength(int declared) {
		if (declared <= 0) {
			return 4;
		}
		int tokens = (declared + MAX_MATCH - 1) / MAX_MATCH;
		return 4 + tokens * 4 + (tokens + 7) / 8;
	}

	/**
	 * Whether the first {@code maxInput} bytes of {@code data} read as a
	 * well-formed LZ11 stream, decoding strictly: every token it reads must be
	 * complete, no back-reference may point before the start of the output,
	 * and the stream must not run out of bytes before its declared length.
	 * True as well when the whole stream decodes inside the bound.
	 *
	 * <p>This is the check that tells a raw record whose first byte happens
	 * to be 0x11 from a compressed one. {@link #decompress} cannot: at the end
	 * of its input it reads -1, takes that as a flag byte with every bit set
	 * and a 16-byte run at displacement 0, and pads its way to the declared
	 * length - so it "decodes" anything, and a measurement built on it
	 * counted 12 raw retail records as compressed. Decoded strictly, every
	 * raw record in the dump faults inside its first 24 bytes (a reference
	 * to bytes not yet written, or bytes that run out), and no genuine entry
	 * faults at all. The bound keeps this a header check rather than the
	 * 42-second trial decode of the whole dump.
	 *
	 * <p>{@code data} may hold only the stream's first bytes, the stream itself
	 * being {@code stored} bytes long - what an archive's parse reads to sniff an entry it has not
	 * read whole (F055). Every "ran out" decision is about the stream, so it asks {@code stored};
	 * only bytes below the bound are read, and {@code data} must hold all of those.
	 */
	public static boolean prefixDecodes(byte[] data, int maxInput, int stored) {
		if (stored < 4 || data.length < Math.min(maxInput, stored)) {
			return false;
		}
		int declared = LittleEndian.u24(data, 1);
		if (declared <= 0) {
			return false;
		}
		int limit = Math.min(maxInput, stored);
		int in = 4, out = 0, flags = 0, mask = 1;
		while (out < declared) {
			if (in >= limit) {
				return in < stored; //ran out of bound: fine; ran out of data: a fault
			}
			if (mask == 1) {
				flags = data[in++] & 0xFF;
				mask = 0x80;
			} else {
				mask >>= 1;
			}
			if ((flags & mask) == 0) {
				if (in >= limit) {
					return in < stored;
				}
				in++;
				out++;
				continue;
			}
			if (in >= limit) {
				return in < stored;
			}
			int b1 = data[in++] & 0xFF;
			int form = b1 >> 4;
			int need = form == 0 ? 2 : form == 1 ? 3 : 1; //bytes the token still needs
			if (in + need > limit) {
				return in + need <= stored;
			}
			int length, disp;
			if (form == 0) {
				int b2 = data[in++] & 0xFF, b3 = data[in++] & 0xFF;
				length = (((b1 & 0x0F) << 4) | (b2 >> 4)) + 0x11;
				disp = (((b2 & 0x0F) << 8) | b3) + 1;
			} else if (form == 1) {
				int b2 = data[in++] & 0xFF, b3 = data[in++] & 0xFF, b4 = data[in++] & 0xFF;
				length = (((b1 & 0x0F) << 12) | (b2 << 4) | (b3 >> 4)) + 0x111;
				disp = (((b3 & 0x0F) << 8) | b4) + 1;
			} else {
				int b2 = data[in++] & 0xFF;
				length = form + 1;
				disp = (((b1 & 0x0F) << 8) | b2) + 1;
			}
			if (disp > out) {
				return false; //a reference to bytes that have not been written
			}
			out += length;
		}
		return true;
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
		//the three-byte size this writes holds 16 MB less one; past it the header would lie (F057)
		if (n > 0xFFFFFF) {
			throw new IllegalArgumentException("LZ11 compression here writes sizes up to 16 MB; this is " + n
					+ " bytes, and its header could not say so.");
		}
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
		int h = LittleEndian.u24(d, i);
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
