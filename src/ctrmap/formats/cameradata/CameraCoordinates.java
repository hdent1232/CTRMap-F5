package ctrmap.formats.cameradata;

import java.io.IOException;

import ctrmap.LittleEndianDataInputStream;
import ctrmap.LittleEndianDataOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Container for camera coordinate data.
 */
public class CameraCoordinates {

	public float yawShift;
	public float pitchShift;
	public float pitch;
	public float yaw;
	public float FOV; //this one is soo fun
	public float distanceFromTarget;
	public float roll;

	/**
	 * Reads one camera's seven floats, or lets the failure out.
	 *
	 * <p>IT CAUGHT AND PRINTED, so a stream that had run out did not fail here: the
	 * fields kept whatever had been set before the end and the caller got an object
	 * that looked read. A truncated camera table therefore produced cameras, not an
	 * error, and the editor offered them with a Save button.
	 */
	public CameraCoordinates(LittleEndianDataInputStream dis) throws IOException {
		this.yawShift = dis.readFloat();
		this.pitchShift = dis.readFloat();
		this.pitch = dis.readFloat();
		this.yaw = dis.readFloat();
		this.FOV = dis.readFloat();
		this.distanceFromTarget = dis.readFloat();
		this.roll = dis.readFloat();
	}

	@Override
	public boolean equals(Object o) {
		if (o != null && o instanceof CameraCoordinates) {
			CameraCoordinates c2 = (CameraCoordinates) o;
			return ctrmap.util.Bytes.impreciseFloatEquals(Math.round(this.FOV), Math.round(c2.FOV))
					&& ctrmap.util.Bytes.impreciseFloatEquals(Math.round(this.distanceFromTarget), Math.round(c2.distanceFromTarget))
					&& ctrmap.util.Bytes.impreciseFloatEquals(Math.round(this.pitch), Math.round(c2.pitch))
					&& ctrmap.util.Bytes.impreciseFloatEquals(Math.round(this.pitchShift), Math.round(c2.pitchShift))
					&& ctrmap.util.Bytes.impreciseFloatEquals(Math.round(this.yaw), Math.round(c2.yaw))
					&& ctrmap.util.Bytes.impreciseFloatEquals(Math.round(this.yawShift), Math.round(c2.yawShift))
					&& ctrmap.util.Bytes.impreciseFloatEquals(Math.round(this.roll), Math.round(c2.roll));
			
		}
		return false;
	}

	public CameraCoordinates() {
		this.yawShift = 0;
		this.pitchShift = 0;
		this.pitch = -45;
		this.yaw = 0;
		this.FOV = 30;
		this.distanceFromTarget = 200;
		this.roll = 0;
	}

	public void write(LittleEndianDataOutputStream dos) throws IOException {
		try {
			dos.writeFloat(yawShift);
			dos.writeFloat(pitchShift);
			dos.writeFloat(pitch);
			dos.writeFloat(yaw);
			dos.writeFloat(FOV);
			dos.writeFloat(distanceFromTarget);
			dos.writeFloat(roll);
		} catch (IOException cannotWrite) {
			//NOT LOGGED: the table is assembled in memory and then stored, so a record that
			//refused to serialise used to become a SHORT buffer written over the area's real
			//camera table - with the editor reporting a save.
			throw new java.io.IOException("a camera's coordinates could not be written: "
				+ ctrmap.util.Bytes.reason(cannotWrite), cannotWrite);
		}
	}
}
