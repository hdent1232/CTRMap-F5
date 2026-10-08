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

	/**
	 * The same seven floats, bit for bit - the bits {@link #write} puts in the file.
	 *
	 * <p>IT ROUNDED EACH TO A WHOLE NUMBER before comparing, and the camera form writes a
	 * camera only when its record differs, so a pitch moved from 10.0 to 10.4 in a field
	 * that offers two decimals read as no edit and was dropped (F123).
	 */
	@Override
	public boolean equals(Object o) {
		return o instanceof CameraCoordinates && java.util.Arrays.equals(bits(), ((CameraCoordinates) o).bits());
	}

	@Override
	public int hashCode() {
		return java.util.Arrays.hashCode(bits());
	}

	/** The seven floats as the file holds them, in file order. */
	private int[] bits() {
		return new int[]{Float.floatToIntBits(yawShift), Float.floatToIntBits(pitchShift),
			Float.floatToIntBits(pitch), Float.floatToIntBits(yaw), Float.floatToIntBits(FOV),
			Float.floatToIntBits(distanceFromTarget), Float.floatToIntBits(roll)};
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
