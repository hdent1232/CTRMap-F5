package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.LittleEndian.putU16;

/**
 * Gives a zone its own STORY TEXT file - the third of the four things a zone
 * header points at, and the last one CTRMap can make private.
 *
 * <p>WHY IT MATTERS. A zone created by an append was a clone of its donor, text
 * id included, so editing the new zone's dialogue rewrote the donor's. Measured
 * on the owner's game before this: zones 536-539 all shared story text 491 with
 * zone 534, so a line written for the new zone appeared in Sootopolis instead -
 * and nothing said so, because nothing knew.
 *
 * <p>THE COPY IS NOT BLANKED, and that is deliberate. A padding spare gets an
 * EMPTY map because a slot nobody asked for should open empty. Text is the
 * opposite case: the zone's SCRIPT cannot be forked - CTRMap does not manage
 * that archive at all - so every created zone runs the donor's events, and
 * those events ask for line numbers. An empty text file under a script that
 * asks for line 12 is a zone that misbehaves in game rather than one that is
 * merely blank. Copying keeps the pair consistent; independence is the goal
 * here, not emptiness.
 *
 * <p>IT IS HANDED ITS SESSION rather than fetching the open one - the same rule
 * WorkspaceSessionTest holds every new class to, and it caught this one too. The
 * map and area forks predate that ceiling and still reach the statics; nothing
 * new may, and the ceiling only falls.
 *
 * <p>ONE PACK CYCLE, like the map and the area: the new ids are
 * {@code length .. length+count-1} and the archive only grows at its tail, so
 * they are written together and the pack takes them all. A new entry inherits
 * the last entry's compression, which is what {@code GARC.storedCompressed}
 * does for a slot past the end, so no override is needed.
 */
public final class TextForker {

	private TextForker() {
	}

	/**
	 * Whether this workspace can fork story text at all.
	 *
	 * <p>The STORYTEXT archive is opened lazily and a game folder without one is
	 * a real situation the editor already handles elsewhere - the NPC form
	 * disables its talker buttons for exactly this. An append must not half-run
	 * because of it, so it is asked before anything is written.
	 */
	public static boolean available(WorkspaceSession ws) {
		return ws != null && ws.getStoryTextGARC() != null;
	}

	/**
	 * Gives each of {@code count} freshly appended zones its own copy of the
	 * text its donor was using, in one pack cycle.
	 *
	 * <p>Mutates the caller's payloads in place, exactly as the map and area
	 * forks do: {@code newZos[i]} is repointed and {@code master}'s text column
	 * is repointed for the matching row.
	 *
	 * <p>TAKES WHICH ZONES, NOT HOW MANY FROM WHERE, for the same reason the area
	 * fork does: an append hands a contiguous run, a repair hands whatever is still
	 * sharing.
	 *
	 * @param zos the zones' ZO containers, parallel to {@code zoneIndices} (mutated)
	 * @param master the master zone-header table (rows repointed in place)
	 * @param zoneIndices which zone each entry of {@code zos} is
	 */
	public static void forkAppendedTexts(WorkspaceSession ws, byte[][] zos, byte[] master,
			int[] zoneIndices) throws IOException {
		int count = zoneIndices == null ? 0 : zoneIndices.length;
		if (count <= 0) {
			return;
		}
		GARC st = ws == null ? null : ws.getStoryTextGARC();
		if (st == null) {
			throw new IOException("This game folder has no STORYTEXT archive, so a new zone cannot be"
					+ " given its own dialogue file.");
		}
		File dir = ws.getExtractionDirectory(ArchiveType.STORYTEXT);
		if (dir == null) {
			throw new IOException("The STORYTEXT extraction directory is unavailable.");
		}
		dir.mkdirs();
		int firstText = st.length;
		if (ws.isPendingArtifact(new File(dir, String.valueOf(firstText)))) {
			throw new IOException("A story text append is already staged and not yet packed.");
		}
		for (int i = 0; i < count; i++) {
			int newText = firstText + i;
			int oldText = u16(zos[i], i32(zos[i], 4) + ZoneResource.TEXT.headerOffset);
			File src = ws.getWorkspaceFile(ArchiveType.STORYTEXT, oldText);
			if (src == null || !src.isFile()) {
				throw new IOException("Story text file " + oldText + " could not be read out of the"
						+ " workspace, so zone " + zoneIndices[i] + " cannot be given a copy of it.");
			}
			File out = new File(dir, String.valueOf(newText));
			Files.write(out.toPath(), Files.readAllBytes(src.toPath()));
			ws.addPersist(out);
			ZoneResource.TEXT.setIn(zos[i], newText);
			int rowOff = zoneIndices[i] * GeometryForker.MASTER_ROW + ZoneResource.TEXT.headerOffset;
			if (rowOff + 2 > master.length) {
				throw new IOException("Master-table row for zone " + zoneIndices[i] + " out of range.");
			}
			putU16(master, rowOff, newText);
		}
	}
}
