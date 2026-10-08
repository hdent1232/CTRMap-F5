package ctrmap.formats.propdata;

import ctrmap.LittleEndianDataInputStream;
import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.gamedef.ArchiveType;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Registers a BuildingModels prop in an area: the textures it draws with that the area's prop
 * pack lacks, imported from a donor area, and a registry entry from the model's retail template.
 *
 * <p>WHY THIS EXISTS (F107). Three paths did it - the prop palette, the prop form's registration
 * of a typed model number, and the map painter's door props - and they disagreed. One asked before
 * growing the area's pack and two did not. One checked that the donor really holds every name and
 * that the merged pack holds every import; the others only that the pack parsed. One told the prop
 * database about the import, so the other two left its per-area names behind the disk. One zeroed
 * the template's event bindings and two kept them, so the same model was bound differently by the
 * button that placed it. One fell back to a bare entry when the template would not read, one
 * failed, and one let the exception through. The answers are decided once, here; what differs
 * between the callers is where the area's bytes go ({@link Area}) and whether the user is asked
 * ({@link Voice}).
 *
 * <p>Not: {@link PropDatabase} - it knows which area holds which model and textures and never
 * changes an area; this changes one.
 */
public final class PropRegistration {

	private PropRegistration() {
	}

	/** The area a registration lands in: written at once by the prop form, staged by the painter's Apply. */
	public interface Area {

		int id();

		/** The zone being edited, which the shared-area rule does not count as another map. */
		int editingZone();

		/** A subfile as it stands, staged edits included. */
		byte[] file(int subfile);

		/** Puts a subfile, or throws with the reason. */
		void put(int subfile, byte[] data) throws IOException;

		ADPropRegistry registry();

		/** The registry gained an entry: marked, or staged. */
		void registryChanged() throws IOException;

		/** Runs {@code then} once what was put is on disk: now, or when a staged Apply commits. */
		void whenWritten(Runnable then);
	}

	/** What a registration asks the user and tells them: the one difference between the buttons. */
	public interface Voice {

		/** Whether to import these textures from the donor area; false leaves everything as it was. */
		boolean importFrom(int donorArea, List<String> textures);

		/** A line for the user: a texture that will draw the area's own pixels, a bare entry. */
		void note(String line);
	}

	/** What was registered. */
	public static final class Registered {

		public final int reference;
		/** The textures put into the area's pack, decoded: what the editor draws the prop with. */
		public final List<H3DTexture> imported;
		/** True when the area registered the model already: nothing was written. */
		public final boolean already;

		Registered(int reference, List<H3DTexture> imported, boolean already) {
			this.reference = reference;
			this.imported = imported;
			this.already = already;
		}
	}

	/**
	 * Registers {@code pm} in {@code area}, or answers the reference it is registered under already.
	 *
	 * @param files the game the model, the donor area and the zone table are read from
	 * @return what was registered; null when the voice declined the import, with nothing written
	 * @throws IllegalStateException with the reason when it cannot be registered - no donor holds its
	 * textures, the area is shared, the merged pack does not verify, no reference is free; nothing is
	 * written then
	 * @throws IOException when the area could not be written
	 */
	public static Registered register(GameFiles files, PropDatabase db, PropDatabase.PropModel pm, Area area, Voice voice)
			throws IOException {
		GARC models = files.archive(ArchiveType.BUILDING_MODELS);
		byte[] model = models == null ? null : PropDatabase.getSubfile(models.getDecompressedEntry(pm.modelIndex), 0);
		if (model == null) {
			throw new IllegalStateException("BuildingModels entry " + pm.modelIndex + " cannot be read, so the textures it"
					+ " draws with cannot be checked");
		}
		Set<String> drawnWith = PropDatabase.getMaterialTextureNames(model);
		//textures the model carries itself: none in retail, but they need no import
		drawnWith.removeAll(PropDatabase.getTexturePackTextureNames(model));
		return register(files, db, pm, drawnWith, area, voice);
	}

	/** The same, handed the names the model draws with: what a suite with no model to parse hands it. */
	public static Registered register(GameFiles files, PropDatabase db, PropDatabase.PropModel pm, Set<String> drawnWith,
			Area area, Voice voice) throws IOException {
		ADPropRegistry reg = area.registry();
		for (ADPropRegistry.ADPropRegistryEntry e : reg.entries.values()) {
			if (e.model == pm.modelIndex) {
				return new Registered(e.reference, Collections.<H3DTexture>emptyList(), true);
			}
		}
		String name = pm.name == null ? "Model " + pm.modelIndex : pm.name;
		//THE ENTRY AND ITS REFERENCE FIRST, so nothing is written for a registration that cannot finish
		List<String> bare = new ArrayList<>(1);
		ADPropRegistry.ADPropRegistryEntry entry = entryFor(pm, name, bare);
		int reference = Math.max(0, pm.modelIndex);
		while (reg.entries.containsKey(reference)) {
			reference++;
		}
		if (reference > 0xFFFF) {
			throw new IllegalStateException("no prop reference is free from " + pm.modelIndex + " up: references are 16-bit");
		}

		byte[] targetPack = area.file(1);
		Set<String> held = PropDatabase.getTexturePackTextureNames(targetPack);
		List<String> missing = new ArrayList<>();
		for (String t : drawnWith) {
			if (!held.contains(t)) {
				missing.add(t);
			}
		}
		List<H3DTexture> imported = new ArrayList<>();
		if (missing.isEmpty()) {
			//nothing to import, so no donor is read: say which names this area holds under other
			//pixels, against the prop's home
			if (!pm.donorAreas.isEmpty() && pm.donorAreas.get(0) != area.id()) {
				sayClashes(area, targetPack, packOf(files, pm.donorAreas.get(0)), drawnWith, name, voice);
			}
		} else {
			int donor = db.findDonorAreaWithTextures(pm, missing);
			if (donor < 0) {
				throw new IllegalStateException(name + " draws with textures this area does not have " + missing
						+ ", and no single area holds them all to import from. Placed without them it would hardlock"
						+ " the game when the area loads.");
			}
			if (!voice.importFrom(donor, missing)) {
				return null;
			}
			//the donor as the workspace holds it: the database is built from the packed archive and
			//can be ahead of or behind what is on disk
			byte[] donorPack = packOf(files, donor);
			if (donorPack == null || !PropDatabase.getTexturePackTextureNames(donorPack).containsAll(missing)) {
				throw new IllegalStateException("area " + donor + " does not hold all of " + missing + " on disk");
			}
			sayClashes(area, targetPack, donorPack, drawnWith, name, voice);
			//refused here when other zones draw from this area: importIntoArea asks the shared-area question
			byte[] merged = BchTexturePack.importIntoArea(files, area.id(), area.editingZone(), targetPack, donorPack, missing);
			if (merged != targetPack) {
				imported.addAll(verified(merged, missing));
				area.put(1, merged);
				final List<String> names = new ArrayList<>(missing);
				final int areaId = area.id();
				area.whenWritten(() -> db.registerImportedTextures(areaId, names));
			}
		}
		entry.model = pm.modelIndex;
		int registered = reg.register(entry, reference);
		area.registryChanged();
		for (String line : bare) {
			voice.note(line);
		}
		return new Registered(registered, imported, false);
	}

	/**
	 * The model's retail registry entry, its event bindings KEPT: they are a door's open/close and
	 * a lamp's day/night hooks, and the template is the only record of them. A model with no
	 * template, or one that will not read, gets a bare entry, and {@code bare} gets the line saying so.
	 */
	static ADPropRegistry.ADPropRegistryEntry entryFor(PropDatabase.PropModel pm, String name, List<String> bare) {
		String setThem = " Set its animations in \"[DANGER] Edit registry data\" if it should animate.";
		if (pm.template == null) {
			bare.add(name + " has no retail registry entry in any area (a custom prop?), so it was registered with a"
					+ " bare one." + setThem);
			return new ADPropRegistry.ADPropRegistryEntry();
		}
		try (LittleEndianDataInputStream in = new LittleEndianDataInputStream(new ByteArrayInputStream(pm.template))) {
			return new ADPropRegistry.ADPropRegistryEntry(in);
		} catch (IOException unreadable) {
			bare.add(name + "'s retail registry entry would not read (" + ctrmap.util.Bytes.reason(unreadable)
					+ "), so it was registered with a bare one." + setThem);
			return new ADPropRegistry.ADPropRegistryEntry();
		}
	}

	/**
	 * The imported textures of a merged pack, decoded, once it is shown to read and to hold every
	 * one of them: checked before the pack is written, not after.
	 *
	 * @throws IllegalStateException when it does not read, or lacks one
	 */
	public static List<H3DTexture> verified(byte[] merged, List<String> imported) {
		BCHFile check = new BCHFile(merged);
		if (check.errorlevel != 0) {
			throw new IllegalStateException("the merged texture pack does not verify (error level " + check.errorlevel + ")");
		}
		List<H3DTexture> out = new ArrayList<>();
		for (H3DTexture t : check.textures) {
			if (imported.contains(t.textureName)) {
				out.add(t);
			}
		}
		if (out.size() != imported.size()) {
			throw new IllegalStateException("the merged texture pack holds " + out.size() + " of the "
					+ imported.size() + " textures imported into it");
		}
		return out;
	}

	private static void sayClashes(Area area, byte[] targetPack, byte[] otherPack, Set<String> drawnWith, String name, Voice voice) {
		for (String c : BchTexturePack.clashesWith(area.file(11), targetPack, otherPack, new ArrayList<>(drawnWith))) {
			voice.note(name + " will draw this area's " + c + ", not its own: the two textures share a name and differ.");
		}
	}

	/** An area's prop texture pack as the workspace holds it, or null when it cannot be read. */
	static byte[] packOf(GameFiles files, int area) throws IOException {
		File f = files.staged(ArchiveType.AREA_DATA, area);
		return f == null || !f.isFile() ? null : PropDatabase.getSubfile(Files.readAllBytes(f.toPath()), 1);
	}
}
