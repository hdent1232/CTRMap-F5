package ctrmap.tests;

import ctrmap.LittleEndianDataOutputStream;
import ctrmap.formats.zone.ZoneEntities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Headless round-trip test for ZoneEntities trigger support.
 *
 * Builds a synthetic, %4-aligned entities blob (header + 1 furniture + 2 NPCs
 * + 1 warp + 2 trigger1 + 1 trigger2 + minimal valid pawn script), parses it
 * with ZoneEntities and asserts that assembleData() reproduces it
 * byte-identically. Then verifies that header counts are derived from live
 * list sizes after an add without touching the count fields.
 */
public class ZoneEntitiesRoundTripTest {

	public static void main(String[] args) throws IOException {
		byte[] data = buildFixture();
		if (data.length % 4 != 0) {
			System.out.println("FIXTURE ERROR: input not %4-aligned (" + data.length + " bytes)");
			System.exit(1);
		}

		ZoneEntities e = new ZoneEntities(data);

		//sanity checks on the parsed triggers
		check(e.trigger1Count == 2, "trigger1Count == 2, got " + e.trigger1Count);
		check(e.trigger2Count == 1, "trigger2Count == 1, got " + e.trigger2Count);
		check(e.triggers1.size() == 2, "triggers1.size() == 2, got " + e.triggers1.size());
		check(e.triggers2.size() == 1, "triggers2.size() == 1, got " + e.triggers2.size());
		ZoneEntities.Trigger t0 = e.triggers1.get(0);
		check(t0.script == 0x1234, "trigger1[0].script == 0x1234, got 0x" + Integer.toHexString(t0.script));
		check(t0.u2 == 0x2345, "trigger1[0].u2 == 0x2345, got 0x" + Integer.toHexString(t0.u2));
		check(t0.constant == 0x4001, "trigger1[0].constant == 0x4001, got 0x" + Integer.toHexString(t0.constant));
		check(t0.u6 == 5, "trigger1[0].u6 == 5, got " + t0.u6);
		check(t0.u8 == 1, "trigger1[0].u8 == 1, got " + t0.u8);
		check(t0.uA == 0xBEEF, "trigger1[0].uA == 0xBEEF (preserved), got 0x" + Integer.toHexString(t0.uA));
		check(t0.x == 12, "trigger1[0].x == 12, got " + t0.x);
		check(t0.y == 34, "trigger1[0].y == 34, got " + t0.y);
		check(t0.w == 2, "trigger1[0].w == 2, got " + t0.w);
		check(t0.h == 3, "trigger1[0].h == 3, got " + t0.h);
		check(t0.u14 == -2, "trigger1[0].u14 == -2, got " + t0.u14);
		check(t0.u16val == -3, "trigger1[0].u16val == -3, got " + t0.u16val);

		byte[] out = e.assembleData();
		if (!Arrays.equals(data, out)) {
			System.out.println("FAIL: round-trip is not byte-identical");
			hexDiff(data, out);
			System.exit(1);
		}

		//count-desync check: mutate the lists only, never the count fields
		ZoneEntities e2 = new ZoneEntities(data);
		e2.triggers1.add(new ZoneEntities.Trigger());
		byte[] out2 = e2.assembleData();
		ZoneEntities e3 = new ZoneEntities(out2);
		check(e3.trigger1Count == 3, "post-add reparse trigger1Count == 3, got " + e3.trigger1Count);
		check(e3.triggers1.size() == 3, "post-add reparse triggers1.size() == 3, got " + e3.triggers1.size());
		check(e3.trigger2Count == 1, "post-add reparse trigger2Count == 1, got " + e3.trigger2Count);
		check(e3.totalLength == 220 + 0x18, "post-add reparse totalLength == 244, got " + e3.totalLength);
		ZoneEntities.Trigger added = e3.triggers1.get(2);
		check(added.w == 1 && added.h == 1 && added.script == 0, "default Trigger round-trips as w=1,h=1,script=0");

		System.out.println("PASS");
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("FAIL: " + what);
			System.exit(1);
		}
	}

	private static void hexDiff(byte[] a, byte[] b) {
		System.out.println("expected " + a.length + " bytes, got " + b.length);
		int max = Math.max(a.length, b.length);
		for (int i = 0; i < max; i++) {
			int av = (i < a.length) ? (a[i] & 0xFF) : -1;
			int bv = (i < b.length) ? (b[i] & 0xFF) : -1;
			if (av != bv) {
				System.out.println(String.format("  offset 0x%04X: expected %s, got %s",
						i,
						av == -1 ? "--" : String.format("%02X", av),
						bv == -1 ? "--" : String.format("%02X", bv)));
			}
		}
	}

	private static byte[] buildFixture() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);

		//entities header: 8 + 1*0x14 + 2*0x30 + 1*0x18 + 2*0x18 + 1*0x18 = 220
		dos.writeInt(220);
		dos.write(1); //furniture
		dos.write(2); //NPCs
		dos.write(1); //warps
		dos.write(2); //trigger1
		dos.write(1); //trigger2
		dos.write(0); //padding
		dos.write(0);
		dos.write(0);

		//furniture record, 0x14 bytes
		writeShorts(dos, new int[]{0x0101, 0x0202, 0x0303, 0x0404, 5, 6, 7, 8});
		dos.writeInt(0x0A0B0C0D);

		//two NPC records, 0x30 bytes each: 22 shorts + float
		for (int n = 0; n < 2; n++) {
			writeShorts(dos, new int[]{
				100 + n, 27, 1, 4, 0x1F0 + n, 3000 + n, 2, 5,
				0, 0, 4, 5, 6, 7,
				-1, -1, -1,
				0, 8, 9,
				20 + n, 21 + n});
			dos.writeFloat(45.5f + n);
		}

		//warp record, 0x18 bytes, coordinateType 0 => x/z/y order on disk
		writeShorts(dos, new int[]{4}); //targetZone
		writeShorts(dos, new int[]{1}); //targetWarpId
		dos.write(1); //faceDirection
		dos.write(3); //transitionType
		writeShorts(dos, new int[]{0, 45, 63, 0, 1, 1, 0, -1, -1});

		//two trigger1 records, 0x18 bytes each
		writeShorts(dos, new int[]{0x1234, 0x2345, 0x4001, 5, 1, 0xBEEF, 12, 34, 2, 3, -2, -3});
		writeShorts(dos, new int[]{0x5678, 0x6789, 0x4002, 8, 0, 0xCAFE, 56, 78, 1, 1, 0, -1});

		//one trigger2 record, 0x18 bytes
		writeShorts(dos, new int[]{0x0F0F, 0x1111, 0x4003, 1, 0, 0xD00D, 90, 12, 4, 2, -4, 7});

		//minimal valid GFLPawnScript blob, 64 bytes:
		//60-byte header, 0 prefix entries, empty rest, 4 bytes of compressed code
		dos.writeInt(64); //len
		dos.writeShort((short) 0xE0F1); //magic
		dos.write(1); //ver
		dos.write(1); //minCompatVer
		dos.writeShort((short) 0); //flags
		dos.writeShort((short) 8); //defsize
		dos.writeInt(60); //instructionStart
		dos.writeInt(60); //dataStart
		dos.writeInt(64); //heapStart
		dos.writeInt(0x1000); //allocatedMem
		dos.writeInt(0); //mainEntryPoint
		dos.writeInt(60); //publicsOffset
		dos.writeInt(60); //nativesOffset
		dos.writeInt(60); //librariesOffset
		dos.writeInt(60); //publicVarsOffset
		dos.writeInt(60); //tagsOffset
		dos.writeInt(60); //namesOffset
		dos.writeInt(60); //overlaysOffset
		dos.write(new byte[]{0x11, 0x22, 0x33, 0x44}); //compCode

		dos.close();
		return baos.toByteArray();
	}

	private static void writeShorts(LittleEndianDataOutputStream dos, int[] values) throws IOException {
		for (int i = 0; i < values.length; i++) {
			dos.writeShort((short) values[i]);
		}
	}
}
