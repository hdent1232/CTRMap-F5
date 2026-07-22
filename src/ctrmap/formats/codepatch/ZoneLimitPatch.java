package ctrmap.formats.codepatch;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-scaling code patch that lifts ORAS's hardcoded 536-zone limit so a
 * fan game (e.g. Delta Emerald) can add new map zones.
 *
 * <p>Reverse-engineered from Omega Ruby's decompressed executable (code.bin;
 * virtual address = file offset + 0x00100000). Stock ORAS bakes the zone layout
 * into five constants: the ZoneData archive holds zones 0..535, then the master
 * header table at entry 536, then the "EN" encounter pack at entry 537. Five
 * places in code hardcode that layout:
 * <ol>
 *   <li>the boot master-table loader reads archive entry 536 (a {@code mov}),</li>
 *   <li>the master-table buffer size is 536 * 0x38 (a literal-pool word),</li>
 *   <li>the EN pack is read from entry 537 (a literal-pool word),</li>
 *   <li>+ (5) two zone-index bound checks reject index &gt;= 536 ({@code cmp}).</li>
 * </ol>
 * Appending a zone shifts the master/EN entries up, but the boot loader still
 * reads entry 536 (now a zone, wrong size) and panics -> black screen at boot.
 *
 * <p>This class raises all five to accommodate N new zones. The master-table
 * archive index and the two bounds are ARM <em>immediate</em> operands, and only
 * multiples of 4 are encodable in that range, so the master index is rounded up
 * to the next multiple of 4 (spare, never-visited zone slots absorb the
 * remainder). Every patch value is derived from that single number M:
 * <pre>
 *   M          = next multiple of 4 &gt;= (536 + N)     // master-table archive index
 *   masterSize = M * 0x38                             // buffer size
 *   enIndex    = M + 1                                // EN pack archive index
 *   bound      = M                                    // zone index must be &lt; M
 * </pre>
 *
 * <p>The patch targets the <b>decompressed</b> code (Luma3DS's {@code code.ips}
 * and Azahar both operate on the decompressed image), so offsets are file
 * offsets into code.bin. IPS generation is self-contained (the stock words are
 * embedded from the RE), so a {@code code.ips} can be produced from N alone;
 * {@link #applyToCode} additionally verifies the stock bytes to guarantee it is
 * patching a matching ORAS build.
 */
public class ZoneLimitPatch {

	/** Stock ORAS zone count (indices 0..535). */
	public static final int BASE_ZONES = 536;
	/** Master-table row size in bytes (one zone header per row). */
	public static final int MASTER_ROW = 0x38;
	/** Omega Ruby title id (for the Luma patch path). */
	public static final String TITLE_ID = "000400000011C400";

	// Site = decompressed-code file offset + stock little-endian 32-bit word.
	// IMM sites are ARM mov/cmp whose imm8 encodes value/4 (rotate 15); patched
	// by replacing imm8 with M/4. WORD sites are literal-pool constants.
	private static final int OFF_MASTER_IDX  = 0x012bc0, W_MASTER_IDX  = 0xE3A01F86; // mov r1,#0x218
	private static final int OFF_BOUND_A     = 0x2d9774, W_BOUND_A     = 0xE3550F86; // cmp r5,#0x218
	private static final int OFF_BOUND_B     = 0x2d99f8, W_BOUND_B     = 0xE3560F86; // cmp r6,#0x218
	private static final int OFF_MASTER_SIZE = 0x012c0c, W_MASTER_SIZE = 0x00007540; // 536*0x38
	private static final int OFF_EN_IDX      = 0x00eae0, W_EN_IDX      = 0x00000219; // 537

	/** Largest N this class can encode (master index M keeps M/4 <= 0xFF). */
	public static final int MAX_NEW_ZONES = 0xFF * 4 - BASE_ZONES; // 484

	/** One 4-byte edit: where, the stock bytes, and the replacement bytes. */
	public static final class Patch {
		public final int fileOffset;
		public final byte[] original;
		public final byte[] patched;
		public final String desc;

		Patch(int off, int orig, int patched, String desc) {
			this.fileOffset = off;
			this.original = le(orig);
			this.patched = le(patched);
			this.desc = desc;
		}
	}

	/** The master-table archive index for N added zones: next multiple of 4 &gt;= 536+N. */
	public static int masterIndex(int newZones) {
		if (newZones < 1) {
			throw new IllegalArgumentException("newZones must be >= 1");
		}
		if (newZones > MAX_NEW_ZONES) {
			throw new IllegalArgumentException("at most " + MAX_NEW_ZONES + " new zones supported");
		}
		return (BASE_ZONES + newZones + 3) & ~3;
	}

	/** How many zone slots the archive must actually contain (real + spares). */
	public static int totalZoneSlots(int newZones) {
		return masterIndex(newZones);
	}

	/** Spare (never-visited) zone slots created to keep the master index a multiple of 4. */
	public static int spareZones(int newZones) {
		return masterIndex(newZones) - (BASE_ZONES + newZones);
	}

	public static List<Patch> patches(int newZones) {
		int m = masterIndex(newZones);
		int imm8 = m / 4; // ARM immediate: (m/4) rotated right 30 == (m/4) << 2 == m
		List<Patch> out = new ArrayList<>();
		out.add(new Patch(OFF_MASTER_IDX, W_MASTER_IDX, (W_MASTER_IDX & ~0xFF) | imm8, "master-table archive index = " + m));
		out.add(new Patch(OFF_BOUND_A, W_BOUND_A, (W_BOUND_A & ~0xFF) | imm8, "zone-index bound A = " + m));
		out.add(new Patch(OFF_BOUND_B, W_BOUND_B, (W_BOUND_B & ~0xFF) | imm8, "zone-index bound B = " + m));
		out.add(new Patch(OFF_MASTER_SIZE, W_MASTER_SIZE, m * MASTER_ROW, "master-table size = " + (m * MASTER_ROW)));
		out.add(new Patch(OFF_EN_IDX, W_EN_IDX, m + 1, "EN-pack archive index = " + (m + 1)));
		return out;
	}

	/**
	 * Builds an IPS patch (for Luma3DS: sdmc:/luma/titles/{@value #TITLE_ID}/code.ips).
	 * Self-contained; does not need the original code.bin.
	 */
	public static byte[] buildIPS(int newZones) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		writeAscii(b, "PATCH");
		for (Patch p : patches(newZones)) {
			int o = p.fileOffset;
			b.write((o >> 16) & 0xFF);
			b.write((o >> 8) & 0xFF);
			b.write(o & 0xFF);        // 3-byte big-endian offset
			b.write(0);
			b.write(4);               // 2-byte big-endian length
			b.write(p.patched, 0, 4);
		}
		writeAscii(b, "EOF");
		return b.toByteArray();
	}

	/**
	 * Returns a patched copy of a decompressed code.bin, verifying every stock
	 * word first (throws if the input is not a matching ORAS build).
	 */
	public static byte[] applyToCode(int newZones, byte[] code) {
		byte[] out = code.clone();
		for (Patch p : patches(newZones)) {
			for (int i = 0; i < 4; i++) {
				if (out[p.fileOffset + i] != p.original[i]) {
					throw new IllegalStateException(String.format(
							"stock byte mismatch at code offset 0x%X (%s): not a matching ORAS code.bin",
							p.fileOffset + i, p.desc));
				}
			}
			System.arraycopy(p.patched, 0, out, p.fileOffset, 4);
		}
		return out;
	}

	private static void writeAscii(ByteArrayOutputStream b, String s) {
		for (int i = 0; i < s.length(); i++) {
			b.write(s.charAt(i));
		}
	}

	private static byte[] le(int v) {
		return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
	}
}
