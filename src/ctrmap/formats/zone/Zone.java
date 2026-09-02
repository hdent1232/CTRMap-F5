package ctrmap.formats.zone;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.Utils;
import ctrmap.Workspace;
import ctrmap.formats.containers.ZO;
import java.util.Arrays;
import javax.swing.JOptionPane;

/**
 * Zone data container class, incomplete.
 */
public class Zone {
	public ZO file;
	public ZoneHeader header;
	public ZoneEntities entities;
	public GFLPawnScript s;
	public Zone(ZO data, Workspace.GameType game){
		file = data;
		header = new ZoneHeader(data.getFile(0), game);
		entities = new ZoneEntities(data.getFile(1));
		s = new GFLPawnScript(data.getFile(2));
	}
	public boolean store(boolean dialog){
		byte[] headerData = header.assembleData();
		if (!Arrays.equals(headerData, file.getFile(0))){
			/*System.out.println(Arrays.toString(headerData));
			System.out.println(Arrays.toString(file.getFile(0)));*/
			if (dialog){
				int result = Utils.showSaveConfirmationDialog("Zone header");
				switch (result){
					case JOptionPane.YES_OPTION:
						file.storeFile(0, headerData);
					case JOptionPane.NO_OPTION:
						break;
					case JOptionPane.CANCEL_OPTION:
						return false;
				}
			}
			else {
				file.storeFile(0, headerData);
			}
		}
		if (entities.modified){
			byte[] entityData;
			try {
				entityData = entities.assembleData();
			} catch (IllegalStateException ex) {
				//a refused record (an unset warp, a NaN altitude, a 256th entity)
				//used to die on the event thread where nobody saw it
				JOptionPane.showMessageDialog(null, ex.getMessage(), "Entity data not saved", JOptionPane.ERROR_MESSAGE);
				return false;
			}
			if (dialog){
				int result = Utils.showSaveConfirmationDialog("Entity data");
				switch (result){
					case JOptionPane.YES_OPTION:
						file.storeFile(1, entityData);
					case JOptionPane.NO_OPTION:
						break;
					case JOptionPane.CANCEL_OPTION:
						return false;
				}
			}
			else {
				file.storeFile(1, entityData);
			}
			entities.modified = false;
		}
		file.storeFile(2, s.getScriptBytes());
		return true;
	}
}
