package ctrmap.formats.propdata;

import ctrmap.LittleEndianDataInputStream;
import ctrmap.LittleEndianDataOutputStream;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.AD;
import ctrmap.formats.containers.BM;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.gamedef.ArchiveType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Basic implementation of the first section of AreaData, the prop registry.
 */
public class ADPropRegistry {

	private AD f;
	public Map<Integer, ADPropRegistryEntry> entries = new LinkedHashMap<>();
	public Map<Integer, H3DModel> models = new HashMap<>();
	/** The models as read or last written: what a discard puts back (F106). */
	private Map<Integer, H3DModel> savedModels = new HashMap<>();
	public boolean modified = false;
	/** References the area held more than once: only the last of each was kept when it was read. */
	private final List<Integer> duplicates = new ArrayList<>();

	/**
	 * The registry's entries only: no model is read, so no game is needed.
	 * What a tool that edits or inspects the table wants.
	 */
	public ADPropRegistry(AD ad) {
		this(ad, null, null, false);
	}

	/**
	 * The registry's entries and, for each, its model read from the handed
	 * game's staged BuildingModels entry, textured with the area's prop
	 * textures. What the viewport wants.
	 *
	 * <p>Handed the game rather than fetching the application's: the models
	 * used to come from the global's workspace file, so the registry could only
	 * ever draw the application's game. {@code ctrmap.tests.HandedGameTest}
	 * hands it two games and reads which one each model was staged from.
	 *
	 * @param files the game the models are read from; null is refused in words
	 */
	public ADPropRegistry(AD ad, List<H3DTexture> textures, GameFiles files) {
		this(ad, textures, handed(files), true);
	}

	/** A handed game must exist when models are wanted: null here would be a registry drawn from no game at all. */
	private static GameFiles handed(GameFiles files) {
		if (files == null) {
			throw new IllegalArgumentException("a prop registry that loads its models must be handed the game"
					+ " they are read from; handed null, there is no model to read (use the entries-only constructor)");
		}
		return files;
	}

	private ADPropRegistry(AD ad, List<H3DTexture> textures, GameFiles files, boolean loadModels) {
		f = ad;
		read();
		if (loadModels) {
			for (ADPropRegistryEntry entry : entries.values()) {
				File model = files.staged(ArchiveType.BUILDING_MODELS, entry.model);
				if (model == null || !model.isFile()) {
					//refused in words, where the container base would have died on a
					//null offset table: a registry naming a model the game does not hold
					throw new IllegalStateException("prop reference " + entry.reference + " names BuildingModels entry "
							+ entry.model + ", which the handed game has not staged (" + model + ")");
				}
				BCHFile bch = new BCHFile(new BM(model, files).getFile(0));
				if (!bch.models.isEmpty()){
					bch.models.get(0).setMaterialTextures(bch.textures);
					if (textures != null){
						bch.models.get(0).setMaterialTextures(textures);
					}
					bch.models.get(0).makeAllBOs();
					models.put(entry.reference, bch.models.get(0));
				}
			}
		}
		savedModels = new HashMap<>(models);
	}

	/**
	 * The entries, from the area: what the registry opens with and what a discard goes back to
	 * (F106). Read whole before anything held is replaced, so a read that fails leaves it as it was.
	 */
	private void read() {
		Map<Integer, ADPropRegistryEntry> read = new LinkedHashMap<>();
		List<Integer> twice = new ArrayList<>();
		try (LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new ByteArrayInputStream(f.getFile(0)))) {
			int numEntries = dis.readInt();
			for (int i = 0; i < numEntries; i++) {
				ADPropRegistryEntry entry = new ADPropRegistryEntry(dis);
				if (read.containsKey(entry.reference)) {
					//the map keeps one entry per reference, so writing this registry back would drop
					//the other: noted here, and serialize() refuses rather than doing it (F084)
					twice.add(entry.reference);
				}
				read.put(entry.reference, entry);
			}
		} catch (IOException ex) {
			//REFUSED, NOT PRINTED (F084): a short read kept the entries read so far, and the next
			//write saved that shorter registry back over the area's
			throw new IllegalStateException("The prop registry of " + (f == null ? "(no area)" : f.getOriginFile().getName())
					+ " could not be read, so it was not opened: " + (ex instanceof java.io.EOFException
					? "it ends before the entries it declares" : ctrmap.util.Bytes.reason(ex)), ex);
		}
		entries.clear();
		entries.putAll(read);
		duplicates.clear();
		duplicates.addAll(twice);
	}

	/**
	 * Puts the entries back to what the area holds and the models to what they were when it was read
	 * or last written, writing nothing (F106). No used to clear the flag and keep the edited and added
	 * entries, where the props went on being drawn from them and the next registry save wrote them.
	 *
	 * @return true when there was a change to put back, false when the registry was not modified
	 * @throws IllegalStateException when the area no longer reads; nothing here is changed then
	 */
	public boolean discard() {
		if (!modified) {
			return false;
		}
		read();
		models.clear();
		models.putAll(savedModels);
		modified = false;
		return true;
	}
	
	public H3DModel getModel(int uid){
		return models.get(uid);
	}
	
	/**
	 * The registry as the area stores it: the count, then every entry in order. The one writer of
	 * the layout (F084) - the map painter wrote the same body a second time, because write() went
	 * straight to the container and could not hand the bytes back.
	 *
	 * @throws IllegalStateException when the area held a reference twice: only one of the two was
	 * kept when it was read, so writing the registry back would drop the other without a word
	 */
	public byte[] serialize() {
		if (!duplicates.isEmpty()) {
			throw new IllegalStateException("This area's prop registry holds reference(s) " + duplicates
					+ " more than once. Only one of each could be kept when it was read, so it is not written"
					+ " back - that would drop the others.");
		}
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);
			dos.writeInt(entries.size());
			for (ADPropRegistryEntry e : entries.values()) {
				e.write(dos);
			}
			dos.close();
			return baos.toByteArray();
		} catch (IOException ex) {
			throw new IllegalStateException("the prop registry could not be assembled: " + ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	/**
	 * Stores the registry in its area when it was changed. {@link #modified} is cleared only once
	 * the store has happened: it was cleared first, so a write that failed was forgotten (F084).
	 */
	public void write() {
		if (!modified) {
			return;
		}
		f.storeFile(0, serialize());
		modified = false;
		savedModels = new HashMap<>(models);
	}

	/**
	 * Puts {@code entry} where the entry {@code oldReference} was - the same place in the written
	 * order. Remove-then-put moved an edited entry to the end of the registry (F084).
	 *
	 * @throws IllegalArgumentException when there is no such entry, or the entry's reference is
	 * already another entry's
	 */
	public void replace(int oldReference, ADPropRegistryEntry entry) {
		if (!entries.containsKey(oldReference)) {
			throw new IllegalArgumentException("no prop registry entry has reference " + oldReference);
		}
		if (entry.reference != oldReference && entries.containsKey(entry.reference)) {
			throw new IllegalArgumentException("reference " + entry.reference + " is already registered");
		}
		Map<Integer, ADPropRegistryEntry> next = new LinkedHashMap<>();
		for (Map.Entry<Integer, ADPropRegistryEntry> e : entries.entrySet()) {
			if (e.getKey() == oldReference) {
				next.put(entry.reference, entry);
			} else {
				next.put(e.getKey(), e.getValue());
			}
		}
		entries.clear();
		entries.putAll(next);
		modified = true;
	}

	/**
	 * Adds {@code entry} under the lowest free reference from {@code preferredReference} up, and
	 * answers it. Every addition goes through here: the registry editor's New entry put each one
	 * under the fixed key -1, so a second click replaced the first in the registry while the list
	 * went on showing both (F084), and two other callers searched for a free reference by hand.
	 * It does not mark the registry modified; a caller that stages the bytes itself does not want it.
	 */
	public int register(ADPropRegistryEntry entry, int preferredReference) {
		int ref = Math.max(0, preferredReference);
		while (entries.containsKey(ref)) {
			ref++;
		}
		if (ref > 0xFFFF) {
			throw new IllegalStateException("no prop reference is free from " + preferredReference
					+ " up: references are 16-bit");
		}
		entry.reference = ref;
		entries.put(ref, entry);
		return ref;
	}

	public static class ADPropRegistryEntry {

		public int reference;
		public int model;
		public int eventScr1; //used for automated prop animation (doors opening on contact etc, probably called from warp scripts, some may (?) return callbacks to the scripting engine when anim is finished)
		public int eventScr2; //used in XY for day/night reacting props such as lamps or some fountains
		public byte u0;
		public byte isOmnipresentAnimated;
		public int u1;
		public short[] omnipresentAnimations = new short[3]; //the engine has 3 animation slots everywhere for the 3 CTR anim types - Skel, mat and vis
		public short[] evtAnimations1 = new short[4]; //for some reason there are extra 2 bytes before the next section. Might just be padding but as I haven't seen them set to anything not FFFF, idk what they are for.
		public short[][] evtAnimations2 = new short[3][3];
		public byte[] u2 = new byte[0x24]; //mostly constant except for the XY aquacorde fountain (maybe more, haven't looked). Changing it had no visible effect.
		//said prop had some CGFX particle data in its files but I couldn't notice those in game. So it might just be related to that and could have been scrapped.

		public ADPropRegistryEntry(LittleEndianDataInputStream dis) throws IOException {
			reference = dis.readUnsignedShort(); //both ref and mdl are the same in GF-made regions afaik but they can be changed
			model = dis.readUnsignedShort(); //ref is the one the GR propdata finds in the registry, mdl is then found in the BM GARC
			eventScr1 = dis.read();
			eventScr2 = dis.read();
			u0 = dis.readByte();
			isOmnipresentAnimated = dis.readByte();
			u1 = dis.readInt();
			for (int i = 0; i < 3; i++) {
				omnipresentAnimations[i] = dis.readShort();
			}
			for (int i = 0; i < 4; i++) {
				evtAnimations1[i] = dis.readShort();
			}
			for (int i = 0; i < 9; i++) {
				evtAnimations2[i / 3][i % 3] = dis.readShort();
			}
			dis.readFully(u2);
		}
		
		public ADPropRegistryEntry(){
			reference = -1;
			model = 0;
			eventScr1 = 0;
			eventScr2 = 0;
			isOmnipresentAnimated = 0;
			u1 = 0;
			for (int i = 0; i < 3; i++) {
				omnipresentAnimations[i] = -1;
			}
			//all four: the field's own note says the fourth is never seen as anything but FFFF
			for (int i = 0; i < 4; i++) {
				evtAnimations1[i] = -1;
			}
			for (int i = 0; i < 9; i++) {
				evtAnimations2[i / 3][i % 3] = -1;
			}
			u2 = new byte[]{-1, -1, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
		}
		
		public void write(LittleEndianDataOutputStream dos) throws IOException{
			dos.writeShort((short)reference);
			dos.writeShort((short)model);
			dos.write(eventScr1);
			dos.write(eventScr2);
			dos.writeByte(u0);
			dos.writeByte(isOmnipresentAnimated);
			dos.writeInt(u1);
			for (int i = 0; i < 3; i++) {
				dos.writeShort(omnipresentAnimations[i]);
			}
			for (int i = 0; i < 4; i++) {
				dos.writeShort(evtAnimations1[i]);
			}
			for (int i = 0; i < 9; i++) {
				dos.writeShort(evtAnimations2[i / 3][i % 3]);
			}
			dos.write(u2);
		}

		/**
		 * A deep copy. The registry editor edits one of these (F084): it rebuilt the entry from
		 * defaults and copied back only the fields it shows, so every edit reset the 36 bytes nobody
		 * models to a constant.
		 */
		public ADPropRegistryEntry copy() {
			ADPropRegistryEntry c = new ADPropRegistryEntry();
			c.reference = reference;
			c.model = model;
			c.eventScr1 = eventScr1;
			c.eventScr2 = eventScr2;
			c.u0 = u0;
			c.isOmnipresentAnimated = isOmnipresentAnimated;
			c.u1 = u1;
			c.omnipresentAnimations = omnipresentAnimations.clone();
			c.evtAnimations1 = evtAnimations1.clone();
			c.evtAnimations2 = new short[evtAnimations2.length][];
			for (int i = 0; i < evtAnimations2.length; i++) {
				c.evtAnimations2[i] = evtAnimations2[i].clone();
			}
			c.u2 = u2.clone();
			return c;
		}
		
		/** Every field, u2 included (F084): it answered true for null and for anything else, and never compared u2. */
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof ADPropRegistryEntry)) {
				return false;
			}
			ADPropRegistryEntry e = (ADPropRegistryEntry) obj;
			return e.reference == reference && e.model == model && e.eventScr1 == eventScr1 && e.eventScr2 == eventScr2
					&& e.u0 == u0 && e.isOmnipresentAnimated == isOmnipresentAnimated && e.u1 == u1
					&& Arrays.equals(e.omnipresentAnimations, omnipresentAnimations) && Arrays.equals(e.evtAnimations1, evtAnimations1)
					&& Arrays.deepEquals(e.evtAnimations2, evtAnimations2) && Arrays.equals(e.u2, u2);
		}

		@Override
		public int hashCode() {
			int h = Objects.hash(reference, model, eventScr1, eventScr2, u0, isOmnipresentAnimated, u1);
			h = 31 * h + Arrays.hashCode(omnipresentAnimations);
			h = 31 * h + Arrays.hashCode(evtAnimations1);
			h = 31 * h + Arrays.deepHashCode(evtAnimations2);
			return 31 * h + Arrays.hashCode(u2);
		}
	}
}
