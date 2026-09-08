package ctrmap.formats.propdata;

import ctrmap.LittleEndianDataInputStream;
import ctrmap.LittleEndianDataOutputStream;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.BM;
import ctrmap.formats.h3d.H3DModelNameGet;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.MapObject;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

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

	public GRProp(LittleEndianDataInputStream dis) {
		try {
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
		} catch (IOException ex) {
			Logger.getLogger(GRProp.class.getName()).log(Level.SEVERE, null, ex);
		}
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
		name = H3DModelNameGet.H3DModelNameGet(new BM(f, files).getFile(0));
	}

	public void write(LittleEndianDataOutputStream dos) {
		try {
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
		} catch (IOException ex) {
			Logger.getLogger(GRProp.class.getName()).log(Level.SEVERE, null, ex);
		}
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
