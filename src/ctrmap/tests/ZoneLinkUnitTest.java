package ctrmap.tests;

import ctrmap.WorkspaceSession;
import ctrmap.ZoneLinker;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Connect zones is one change: both ends, or neither (F227).
 *
 * <p>WHY THIS SUITE EXISTS. A link wrote zone A's warp and then zone B's, each through its own
 * container and each staged for the next pack as it went. When B could not be written, A stayed
 * written: a one-way door, the outcome ZoneLinker's own javadoc calls worse than none. The two
 * writes now run in one WorkspaceUnit, and B failing puts A back.
 *
 * <p>ORDER: needs no dump. The workspace is a scratch folder holding two extracted zones, which
 * the session hands back without opening any archive.
 *
 * Usage: java ctrmap.tests.ZoneLinkUnitTest
 */
public class ZoneLinkUnitTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		aLinkThatWorksWritesBothEnds();
		aLinkWhoseSecondEndFailsLeavesTheFirstAsItWas();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** One warp, and an NPC whose uid is {@code npcUid} when {@code npcUid} is not negative. */
	static byte[] entities(int npcUid) {
		byte[] script = HandedGameTest.amx();
		int npcs = npcUid < 0 ? 0 : 1;
		byte[] e = new byte[12 + npcs * 0x30 + 0x18 + script.length];
		MapResizeTest.putI32(e, 0, 8 + npcs * 0x30 + 0x18);
		e[5] = (byte) npcs;
		e[6] = 1;
		if (npcs > 0) {
			MapResizeTest.putU16(e, 12, npcUid);
		}
		System.arraycopy(script, 0, e, 12 + npcs * 0x30 + 0x18, script.length);
		return e;
	}

	static WorkspaceSession workspace(String name, byte[] zone0, byte[] zone1) throws Exception {
		File root = Scratch.dir(name);
		File game = new File(root, "game");
		game.mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "ws"), game, GameType.ORAS);
		File zones = ws.getExtractionDirectory(ArchiveType.ZONE_DATA);
		zones.mkdirs();
		byte[] script = HandedGameTest.amx();
		Files.write(new File(zones, "0").toPath(), HandedGameTest.container("ZO", new byte[0x38], zone0, script));
		Files.write(new File(zones, "1").toPath(), HandedGameTest.container("ZO", new byte[0x38], zone1, script));
		return ws;
	}

	static ZoneEntities warpsOf(WorkspaceSession ws, int zone) {
		return new ZoneEntities(new ZO(ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone), ws).getFile(1));
	}

	static void aLinkThatWorksWritesBothEnds() throws Exception {
		System.out.println("--- control: a link that works writes both ends (F227)");
		WorkspaceSession ws = workspace("ctrmap_link_ok", entities(-1), entities(0));
		String said = ZoneLinker.link(ws, 0, 0, 1, 0, true);
		ZoneEntities.Warp a = warpsOf(ws, 0).warps.get(0);
		ZoneEntities.Warp b = warpsOf(ws, 1).warps.get(0);
		check(a.targetZone == 1 && b.targetZone == 0 && said.contains("lead to each other"),
				"zone 0's warp leads to zone 1 and back (" + a.targetZone + ", " + b.targetZone + ") - the fixture can be written");
	}

	static void aLinkWhoseSecondEndFailsLeavesTheFirstAsItWas() throws Exception {
		System.out.println("--- a link whose second end cannot be written leaves the first as it was (F227)");
		WorkspaceSession ws = workspace("ctrmap_link_half", entities(-1), entities(5));
		File first = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		byte[] before = Files.readAllBytes(first.toPath());
		String refused;
		try {
			ZoneLinker.link(ws, 0, 0, 1, 0, true);
			refused = "nothing was refused";
		} catch (Exception ex) {
			refused = String.valueOf(ex.getMessage());
		}
		check(refused.contains("uid"), "control: zone 1's misnumbered NPC refuses to be written: " + refused);
		check(Arrays.equals(before, Files.readAllBytes(first.toPath())),
				"zone 0 is byte for byte as it was - its warp was written first and stayed, a one-way door");
		check(!ws.persistPaths().contains(first.getAbsolutePath()),
				"and it is not staged for the next pack either");
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
