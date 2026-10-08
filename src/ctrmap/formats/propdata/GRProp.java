package ctrmap.formats.propdata;

import ctrmap.util.LittleEndianDataInputStream;
import ctrmap.util.LittleEndianDataOutputStream;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.BM;
import ctrmap.formats.h3d.H3DModelNameGet;
import ctrmap.gamedef.ArchiveType;
import ctrmap.formats.MapObject;
import java.io.File;
import java.io.IOException;

public class GRProp implements MapObject{

	public int uid; //model ID in a/0/2/4 for XY, literal file number of course
	public float scaleX;
	public float scaleY;
	public float scaleZ;
	public float rotateY;
	public float rotateX;
	public float rotateZ;
	public float x;
	public float y;
	public float z;
	public int unknown;
	public String name; //NONSTANDARD field - retrieved from the prop's H3D model for UX. NOT part of the struct.
	public int nameWidth; //NONSTANDARD
	public int nameHeight; //NONSTANDARD - w and h are present as placeholders for the GUI representations of the props, set with their paintComponent

	/**
	 * Reads one prop.
	 *
	 * @throws IOException when the bytes run out - NOT a prop of zeros. Caught and logged here,
	 *         a section that said it held five props and held none read as five props at the
	 *         map's origin, which the next save then wrote back as real ones.
	 */
	public GRProp(LittleEndianDataInputStream dis) throws IOException {
		uid = dis.readInt();
		scaleX = dis.readFloat();
		scaleY = dis.readFloat();
		scaleZ = dis.readFloat();
		rotateX = dis.readFloat();
		rotateY = dis.readFloat();
		rotateZ = dis.readFloat();
		x = dis.readFloat();
		y = dis.readFloat();
		z = dis.readFloat();
		unknown = dis.readInt();
	}

	public GRProp() {
		uid = 0; //com_bm_dummy
		scaleX = 1f;
		scaleY = 1f;
		scaleZ = 1f;
		rotateX = 0f;
		rotateY = 0f;
		rotateZ = 0f;
		x = 0f;
		y = 0f;
		z = 0f;
		unknown = 0;
	}

	/**
	 * This record whole - the four-byte tail no widget shows included - so an edit changes only what
	 * it edits. The prop editor built a fresh record on every Save, and the tail went to the file as
	 * zero whatever it held (F161).
	 */
	public GRProp copy() {
		GRProp c = new GRProp();
		c.uid = uid;
		c.scaleX = scaleX;
		c.scaleY = scaleY;
		c.scaleZ = scaleZ;
		c.rotateX = rotateX;
		c.rotateY = rotateY;
		c.rotateZ = rotateZ;
		c.x = x;
		c.y = y;
		c.z = z;
		c.unknown = unknown;
		c.name = name;
		c.nameWidth = nameWidth;
		c.nameHeight = nameHeight;
		return c;
	}

	/**
	 * Whether {@code o} is the same 44-byte record, the tail included: the editor's test left it
	 * out, so a record that differed only there was "unchanged" and never written (F161).
	 */
	public boolean sameRecord(GRProp o) {
		return uid == o.uid && x == o.x && y == o.y && z == o.z && rotateX == o.rotateX && rotateY == o.rotateY
				&& rotateZ == o.rotateZ && scaleX == o.scaleX && scaleY == o.scaleY && scaleZ == o.scaleZ
				&& unknown == o.unknown;
	}

	/**
	 * Reads this prop's display name from its model in the handed game: the
	 * model the registry maps its uid to, or - not in the registry - the
	 * BuildingModels entry of the uid itself.
	 *
	 * <p>Handed the game rather than fetching the application's: this used to
	 * read the global's workspace file, so a prop could only ever be named
	 * from the application's game, and with no workspace open every prop was
	 * "Model not found". {@code ctrmap.tests.HandedGameTest} hands it two games
	 * and reads two names.
	 *
	 * @param reg the area's registry, or null to name by uid alone
	 * @param files the game the model is read from; null is refused in words
	 */
	public void updateName(ADPropRegistry reg, GameFiles files) {
		if (files == null) {
			throw new IllegalArgumentException("a prop must be handed the game its model is read from;"
					+ " handed null, there is no model to name it by");
		}
		int model = (reg != null && reg.entries.containsKey(uid)) ? reg.entries.get(uid).model : uid;
		File f = files.staged(ArchiveType.BUILDING_MODELS, model);
		if (f == null || !f.exists()) {
			name = "Model not found";
			return;
		}
		try {
			name = H3DModelNameGet.H3DModelNameGet(new BM(f, files).getFile(0));
		} catch (IllegalStateException unreadable) {
			//the prop is still placed and saved by its uid; only its caption could not be read (F069)
			name = "Model " + model + " (its name cannot be read)";
		}
	}

	/** Writes one prop; a failure is the caller's to report, never a half-written prop called saved. */
	public void write(LittleEndianDataOutputStream dos) throws IOException {
		dos.writeInt(uid);
		dos.writeFloat(scaleX);
		dos.writeFloat(scaleY);
		dos.writeFloat(scaleZ);
		dos.writeFloat(rotateX);
		dos.writeFloat(rotateY);
		dos.writeFloat(rotateZ);
		dos.writeFloat(x);
		dos.writeFloat(y);
		dos.writeFloat(z);
		dos.writeInt(unknown);
	}

	@Override
	public float getX() {
		return x;
	}

	@Override
	public float getY() {
		return y;
	}

	@Override
	public float getZ() {
		return z;
	}

	@Override
	public void setX(float val) {
		x = val;
	}

	@Override
	public void setY(float val) {
		y = val;
	}

	@Override
	public void setZ(float val) {
		z = val;
	}
}
