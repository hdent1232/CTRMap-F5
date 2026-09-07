package ctrmap.formats.zone;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.Utils;
import ctrmap.Workspace;
import ctrmap.formats.containers.ZO;
import ctrmap.gamedef.GameType;
import java.util.Arrays;

/**
 * Zone data container class, incomplete.
 */
public class Zone {
	public ZO file;
	public ZoneHeader header;
	public ZoneEntities entities;
	public GFLPawnScript s;
	public Zone(ZO data, GameType game){
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
			switch (Utils.askToKeep(dialog, "Zone header")) {
				case SAVE:
					file.storeFile(0, headerData);
					break;
				//cancel stops the save rather than quietly dropping this piece of it
				case CANCEL:
					return false;
			}
		}
		if (entities.modified){
			byte[] entityData;
			try {
				entityData = entities.assembleData();
			} catch (IllegalStateException ex) {
				//a refused record (an unset warp, a NaN altitude, a 256th entity)
				//used to die on the event thread where nobody saw it. getMessage()
				//is null for a thrower that named no reason, and a report that
				//reads "null" is the silent failure again, so name the throw.
				ctrmap.Ui.error(null, ctrmap.Ui.reason(ex), "Entity data not saved");
				return false;
			}
			switch (Utils.askToKeep(dialog, "Entity data")) {
				case SAVE:
					file.storeFile(1, entityData);
					break;
				case CANCEL:
					return false;
			}
			entities.modified = false;
		}
		file.storeFile(2, s.getScriptBytes());
		return true;
	}
}
