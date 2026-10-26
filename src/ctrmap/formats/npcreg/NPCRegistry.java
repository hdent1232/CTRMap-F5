package ctrmap.formats.npcreg;

import ctrmap.util.LittleEndianDataOutputStream;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.MM;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.gamedef.ArchiveType;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provides access to and manipulation of MoveModel/NPC registry, specified in a
 * Zone header by the shared AD ushort.
 *
 * <p>Handed the game its models come from and its writes go to, rather than
 * fetching the application's: every model used to be read from the global's
 * workspace file and every write recorded in the global's edited-file list,
 * so a registry could only ever belong to the application's game, and a
 * write made with no workspace open was recorded nowhere. It also asked the
 * user whether to keep its changes, from inside the format layer, which no
 * suite could answer and no caller could decide for it; the question now
 * belongs to the window that owns it (the NPC form), and this class only
 * {@link #store() writes} or {@link #discard() puts back} and says which.
 * {@code ctrmap.tests.HandedGameTest} hands it two games.
 */
public class NPCRegistry {

	/** One entry's length: the format has no count, so the file is as many as fit. */
	public static final int ENTRY = 0x18;

	/** Every entry in file order - a UID held twice included, which a map by UID cannot hold (F081). */
	private final List<NPCRegistryEntry> all = new ArrayList<>();
	private final Map<Integer, NPCRegistryEntry> byUid = new LinkedHashMap<>();
	/** The bytes after the last whole entry, written back as they were read. */
	private byte[] tail = new byte[0];

	/**
	 * The entries by UID, in file order. READ-ONLY (F081): it was a HashMap every caller put into
	 * and removed from, so the registry was written in hash order, a UID held twice (retail carries
	 * UID-0 dummies) was dropped when read and again when written, and nothing could keep the
	 * bytes after the last entry. Every change goes through {@link #register}, {@link #replace} or
	 * {@link #remove}.
	 */
	public final Map<Integer, NPCRegistryEntry> entries = Collections.unmodifiableMap(byUid);
	public Map<Integer, H3DModel> models = new HashMap<>();
	/** The models as read or last stored: what a discard puts back (F106). */
	private Map<Integer, H3DModel> savedModels = new HashMap<>();
	public boolean modified = false;
	private File f;
	/** The game the models are staged in and the write is reported to. */
	private final GameFiles files;

	/**
	 * Reads the registry at {@code f} and, for each entry, its model from the
	 * handed game's staged MoveModels entry.
	 *
	 * @param files the game; null is refused in words
	 */
	public NPCRegistry(File f, GameFiles files) {
		this.f = f;
		this.files = handed(files);
		read();
		for (NPCRegistryEntry e : byUid.values()) {
			mapModel(e.uid, e.model);
		}
		savedModels = new HashMap<>(models);
	}

	/**
	 * Every entry and the bytes after them, from the file: what the registry opens with and what a
	 * discard goes back to (F106). Read whole before anything held is replaced, so a read that fails
	 * leaves the registry as it was.
	 */
	private void read() {
		byte[] b;
		try {
			b = Files.readAllBytes(f.toPath());
		} catch (IOException ex) {
			//REFUSED, NOT LOGGED (F081): an empty registry stood in for one that could not be read,
			//and the next store wrote that emptiness over it
			throw new IllegalStateException("The NPC registry " + f.getName() + " could not be read, so it was not opened: "
					+ ctrmap.util.Bytes.reason(ex), ex);
		}
		//this struct does not describe length in any way. idk why. it works when I add new entries, so it's not described anywhere, even externally.
		int whole = b.length / ENTRY * ENTRY;
		List<NPCRegistryEntry> read = new ArrayList<>();
		for (int at = 0; at < whole; at += ENTRY) {
			read.add(new NPCRegistryEntry(b, at));
		}
		all.clear();
		all.addAll(read);
		tail = Arrays.copyOfRange(b, whole, b.length);
		reindex();
	}

	/** The map by UID, rebuilt from the file-order list: where a UID is held twice, the later one answers. */
	private void reindex() {
		byUid.clear();
		for (NPCRegistryEntry e : all) {
			byUid.put(e.uid, e);
		}
	}

	/** Every entry in file order (F209), a UID held twice included: what an undo history reads. READ-ONLY. */
	public List<NPCRegistryEntry> inFileOrder() {
		return Collections.unmodifiableList(all);
	}

	/**
	 * Puts the registry back as an undo history read it (F209): {@code entries}, in file order - the
	 * history's own copies, which become the registry's - indexed again, and marked changed from what was
	 * saved. The entries held before are let go: the registry editor replaces entries by UID, not in place.
	 */
	public void restore(List<NPCRegistryEntry> entries) {
		all.clear();
		all.addAll(entries);
		reindex();
		modified = true;
	}

	private int indexOf(NPCRegistryEntry e) {
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i) == e) {
				return i;
			}
		}
		throw new IllegalStateException("an NPC registry entry is mapped by UID but not in the registry");
	}

	/**
	 * Adds {@code entry} under the lowest free UID from {@code preferredUid} up, after the entries
	 * already there, and answers the UID. The registry editor's Add used the default UID 0, which
	 * replaced the UID-0 dummy retail registries carry (F081).
	 *
	 * <p>THE CAP IS KEPT HERE (census M.57), the one door every entry comes in by: three callers each
	 * checked {@link #MAX_ENTRIES} before calling, one of them against a literal 31, and a fourth would
	 * have had to remember to.
	 *
	 * @throws IllegalStateException when the registry already holds {@link #MAX_ENTRIES}
	 */
	public int register(NPCRegistryEntry entry, int preferredUid) {
		if (byUid.size() >= MAX_ENTRIES) {
			throw new IllegalStateException("this area's NPC registry already holds its maximum of " + MAX_ENTRIES
					+ " unique NPCs");
		}
		int uid = Math.max(0, preferredUid);
		while (byUid.containsKey(uid)) {
			uid++;
		}
		if (uid > 0xFFFF) {
			throw new IllegalStateException("no NPC registry UID is free from " + preferredUid + " up: UIDs are 16-bit");
		}
		entry.uid = uid;
		all.add(entry);
		byUid.put(uid, entry);
		modified = true;
		return uid;
	}

	/**
	 * Puts {@code entry} where the entry with {@code oldUid} was - its place in the file and the
	 * bytes of its own the editor does not show (F081): the editor removed it and put a new one
	 * built from defaults.
	 *
	 * @throws IllegalArgumentException when there is no such entry, or the entry's UID is another's
	 */
	public void replace(int oldUid, NPCRegistryEntry entry) {
		NPCRegistryEntry old = byUid.get(oldUid);
		if (old == null) {
			throw new IllegalArgumentException("no NPC registry entry has UID " + oldUid);
		}
		if (entry.uid != oldUid && byUid.containsKey(entry.uid)) {
			throw new IllegalArgumentException("UID " + entry.uid + " is already registered");
		}
		all.set(indexOf(old), entry);
		reindex();
		if (!byUid.containsKey(oldUid)) {
			models.remove(oldUid);
		}
		modified = true;
	}

	/** Removes the entry with {@code uid}; where the UID is held twice, the one the map answered. */
	public void remove(int uid) {
		NPCRegistryEntry old = byUid.get(uid);
		if (old == null) {
			throw new IllegalArgumentException("no NPC registry entry has UID " + uid);
		}
		all.remove(indexOf(old));
		reindex();
		if (!byUid.containsKey(uid)) {
			models.remove(uid);
		}
		modified = true;
	}

	/** A handed game must exist: null here would be models read from nowhere and a write nobody packs. */
	private static GameFiles handed(GameFiles files) {
		if (files == null) {
			throw new IllegalArgumentException("an NPC registry must be handed the game its models are staged in"
					+ " and its writes are reported to; handed null, every edit through it would go unrecorded and unpacked");
		}
		return files;
	}

	/**
	 * The BCH of one MoveModels entry, from the handed game's staged copy.
	 * Refuses in words where the container base would have died on a null
	 * offset table: an entry the game has not staged is a registry naming a
	 * model the game does not hold.
	 */
	private static BCHFile moveModel(GameFiles files, int moveModelsIndex) {
		File staged = files.staged(ArchiveType.MOVE_MODELS, moveModelsIndex);
		if (staged == null || !staged.isFile()) {
			throw new IllegalStateException("MoveModels entry " + moveModelsIndex
					+ " is not staged in the handed game (" + staged + ")");
		}
		return new BCHFile(new MM(staged, files).getFile(0));
	}

	public void mapModel(int uid, int mdlnum) {
		BCHFile bch = moveModel(files, mdlnum);
		if (!bch.models.isEmpty()) {
			bch.models.get(0).setMaterialTextures(bch.textures);
			bch.models.get(0).makeAllBOs();
			models.put(uid, bch.models.get(0));
		}
	}

	/**
	 * Soft cap on unique models per area. The registry format itself has no length
	 * field, but the game's per-area model table is assumed bounded; 31 is the
	 * long-standing safe value CTRMap uses. (Raising it is a code.bin RE question.)
	 */
	public static final int MAX_ENTRIES = 31;

	public H3DModel getModel(int uid) {
		return models.get(uid);
	}

	/**
	 * Makes a global MoveModels index usable in THIS area by adding a registry
	 * entry for it, and returns the local UID to reference it by. If the model is
	 * already registered here its existing UID is returned (no duplicate). Returns
	 * -1 when the registry is at capacity. The new entry uses default
	 * collision/render props (as with the dummy-entry failsafe); MoveModel BCHs
	 * carry their own textures, so nothing else needs importing.
	 */
	public int registerModel(int moveModelsIndex) {
		for (NPCRegistryEntry e : entries.values()) {
			if (e.model == moveModelsIndex) {
				return e.uid;
			}
		}
		if (entries.size() >= MAX_ENTRIES) {
			return -1;
		}
		NPCRegistryEntry ne = new NPCRegistryEntry();
		ne.model = moveModelsIndex;
		int uid = register(ne, byUid.containsKey(moveModelsIndex) ? 1 : moveModelsIndex);
		mapModel(uid, moveModelsIndex);
		return uid;
	}

	/**
	 * Loads a fresh, uncached H3DModel straight from a MoveModels index of the
	 * handed game (for previewing a model that is not yet registered in this
	 * area). Returns null if the index has no mesh; refuses null in words.
	 */
	public static H3DModel loadFreshModelByIndex(GameFiles files, int moveModelsIndex) {
		handed(files);
		try {
			BCHFile bch = moveModel(files, moveModelsIndex);
			if (bch.models.isEmpty()) {
				return null;
			}
			H3DModel m = bch.models.get(0);
			m.setMaterialTextures(bch.textures);
			return m;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Loads a FRESH, uncached H3DModel for a registered NPC UID. Unlike
	 * getModel(), this never returns the shared cached instance, so it is safe
	 * to render in a throwaway preview widget (with its own GL buffer objects)
	 * without corrupting the main viewport's copy. Returns null if the UID is
	 * not registered or its model has no mesh.
	 */
	public H3DModel loadFreshModel(int uid) {
		NPCRegistryEntry e = entries.get(uid);
		if (e == null) {
			return null;
		}
		try {
			BCHFile bch = moveModel(files, e.model);
			if (bch.models.isEmpty()) {
				return null;
			}
			H3DModel m = bch.models.get(0);
			m.setMaterialTextures(bch.textures);
			return m;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Writes the registry to its file and reports the write to the handed
	 * game, when there are changes to write.
	 *
	 * <p>This used to ask the user first, from inside the format layer, and
	 * used to log a failed write and answer as if it had succeeded, so the
	 * caller's save sequence carried on over a registry that was never
	 * written. Now the decision is the caller's and the failure is theirs to
	 * report: nothing here shows a person anything.
	 *
	 * @return true when the file was written, false when nothing was modified
	 * and nothing was written
	 * @throws IOException when the file could not be written; the registry
	 * stays marked modified and nothing is reported edited
	 */
	public boolean store() throws IOException {
		if (!modified) {
			return false;
		}
		//EVERY ENTRY IN FILE ORDER AND THE BYTES AFTER THEM (F081), written beside the file and moved
		//over it: this wrote a HashMap in hash order through a stream that truncated the file first
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);
		for (NPCRegistryEntry e : all) {
			e.write(dos);
		}
		dos.write(tail);
		dos.close();
		files.aboutToWrite(f);
		ctrmap.util.Bytes.replaceFile(f, baos.toByteArray());
		//reported after the write, not before: a mark taken first and then a
		//failed write would send the next pack an unwritten file
		files.edited(f);
		modified = false;
		savedModels = new HashMap<>(models);
		return true;
	}

	/**
	 * Puts the entries back to what the file holds and the models to what they were when it was read
	 * or last stored, writing nothing (F106). It used to forget that the registry was modified and
	 * leave the edited entries in memory - drawn, and written by the next store of anything that
	 * registered a model.
	 *
	 * @return true when there were changes to put back, false when the registry was not modified
	 * @throws IllegalStateException when the file no longer reads; nothing here is changed then
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

	public static class NPCRegistryEntry {

		public int uid;
		public int u1; //always 1 except for 0 UID dummies, purpose unknown
		public int renderEnabled;
		public int u4; //if set, 2 or 4, has something to do with NPCs being in cutscenes
		public int u5; //always 0
		public int shadowEnabled;
		public int isItOb; //usually 1 but 0 on it**** and ob****, those are referenced by non-model UIDs, but changing it to 1 doesnt break anything so idk.
		public int u8; //always 1 except for 0 UID dummies but does nothing afaik
		public int u9; //only used in XY NPCs AND XY NPCs present in OA like the Battle Maison receptionist
		public int collW;
		public int collH;
		public int uC; //only used for cut animation, could be something like hide on interact? Cut trees get replaced with animated ones from a181 (XY) so for that maybe, but why isn't it on rock smash then?
		public int uD; //always 0
		public int uE; //always 0
		public int isDummy; //1 on XY dummies (not 0 UID entries but actual dummy models with the dummy BCH name) and thats about it
		public int u10; //always 0
		public int u11; //1 or 2, always used but 0 does not hurt anything.
		public int u12; //always 0
		public int u13; //always 0
		public int model;
		public int u16; //always 0
		public int u17; //always 0

		/**
		 * One entry, read from the {@link NPCRegistry#ENTRY} bytes of {@code b} at {@code at}. FROM THE BYTES, NOT A STREAM
		 * (2026-10-24): the registry is read whole and cut into whole entries, so a stream over it could never run out,
		 * and the catch it needed was a line no input reaches (the 2026-10-17 sweep, NPCRegistry 106, a HOLE).
		 */
		public NPCRegistryEntry(byte[] b, int at) {
			uid = ctrmap.util.LittleEndian.u16(b, at);
			u1 = b[at + 2] & 0xFF;
			renderEnabled = b[at + 3] & 0xFF;
			u4 = b[at + 4] & 0xFF;
			u5 = b[at + 5] & 0xFF;
			shadowEnabled = b[at + 6] & 0xFF;
			isItOb = b[at + 7] & 0xFF;
			u8 = b[at + 8] & 0xFF;
			u9 = b[at + 9] & 0xFF;
			collW = b[at + 10] & 0xFF;
			collH = b[at + 11] & 0xFF;
			uC = b[at + 12] & 0xFF;
			uD = b[at + 13] & 0xFF;
			uE = b[at + 14] & 0xFF;
			isDummy = b[at + 15] & 0xFF;
			u10 = b[at + 16] & 0xFF;
			u11 = b[at + 17] & 0xFF;
			u12 = b[at + 18] & 0xFF;
			u13 = b[at + 19] & 0xFF;
			model = ctrmap.util.LittleEndian.u16(b, at + 20);
			u16 = b[at + 22] & 0xFF;
			u17 = b[at + 23] & 0xFF;
		}

		public NPCRegistryEntry() {
			uid = 0;
			u1 = 0;
			renderEnabled = 2;
			u4 = 0;
			u5 = 0;
			shadowEnabled = 1;
			isItOb = 1;
			u8 = 1;
			u9 = 0;
			collW = 1;
			collH = 1;
			uC = 0;
			uD = 0;
			uE = 0;
			isDummy = 0;
			u10 = 0;
			//the right way round (F081): this set u12 - "always 0" - to 2 and u11 - "1 or 2, always
			//used" - to 0, one field off
			u11 = 2;
			u12 = 0;
			u13 = 0;
			model = 0;
			u16 = 0;
			u17 = 0;
		}

		public void write(LittleEndianDataOutputStream dos) throws IOException {
			dos.writeShort((short) uid);
			dos.write(u1);
			dos.write(renderEnabled);
			dos.write(u4);
			dos.write(u5);
			dos.write(shadowEnabled);
			dos.write(isItOb);
			dos.write(u8);
			dos.write(u9);
			dos.write(collW);
			dos.write(collH);
			dos.write(uC);
			dos.write(uD);
			dos.write(uE);
			dos.write(isDummy);
			dos.write(u10);
			dos.write(u11);
			dos.write(u12);
			dos.write(u13);
			dos.writeShort((short) model);
			dos.write(u16);
			dos.write(u17);
		}

		/**
		 * A copy to edit (F081): the editor built the edited entry from defaults and copied back only
		 * what it shows, so u5, uD, uE, u10, u12, u13, u16 and u17 took the defaults on every edit.
		 */
		public NPCRegistryEntry copy() {
			NPCRegistryEntry c = new NPCRegistryEntry();
			c.uid = uid;
			c.u1 = u1;
			c.renderEnabled = renderEnabled;
			c.u4 = u4;
			c.u5 = u5;
			c.shadowEnabled = shadowEnabled;
			c.isItOb = isItOb;
			c.u8 = u8;
			c.u9 = u9;
			c.collW = collW;
			c.collH = collH;
			c.uC = uC;
			c.uD = uD;
			c.uE = uE;
			c.isDummy = isDummy;
			c.u10 = u10;
			c.u11 = u11;
			c.u12 = u12;
			c.u13 = u13;
			c.model = model;
			c.u16 = u16;
			c.u17 = u17;
			return c;
		}

		/** All 22 fields (F081): the eight left out are exactly the ones an edit lost, so the change check could not see it. */
		@Override
		public boolean equals(Object o2) {
			if (!(o2 instanceof NPCRegistryEntry)) {
				return false;
			}
			NPCRegistryEntry e = (NPCRegistryEntry) o2;
			return e.uid == uid && e.u1 == u1 && e.renderEnabled == renderEnabled && e.u4 == u4 && e.u5 == u5
					&& e.shadowEnabled == shadowEnabled && e.isItOb == isItOb && e.u8 == u8
					&& e.u9 == u9 && e.collW == collW && e.collH == collH && e.uC == uC && e.uD == uD && e.uE == uE
					&& e.isDummy == isDummy && e.u10 == u10 && e.u11 == u11 && e.u12 == u12 && e.u13 == u13
					&& e.model == model && e.u16 == u16 && e.u17 == u17;
		}

		@Override
		public int hashCode() {
			return Objects.hash(uid, u1, renderEnabled, u4, u5, shadowEnabled, isItOb, u8, u9, collW, collH, uC, uD, uE,
					isDummy, u10, u11, u12, u13, model, u16, u17);
		}
	}
}
