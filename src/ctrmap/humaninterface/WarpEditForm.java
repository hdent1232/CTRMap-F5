package ctrmap.humaninterface;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.Workspace;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.zone.WarpTransitions;
import ctrmap.formats.zone.ZoneEntities;
import java.awt.Point;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFormattedTextField;
import javax.swing.text.NumberFormatter;

public class WarpEditForm extends javax.swing.JPanel {

	public ZoneEntities e;
	public ZoneEntities.Warp warp;
	//where warp sits in e.warps. Two warps on one tile are field-for-field
	//identical, so the list is addressed by this index and never by lookup
	private int warpIndex = -1;
	public boolean loaded = false;

	public DefaultComboBoxModel<String> transitionModel = new DefaultComboBoxModel<>();

	public void loadFromEntities(ZoneEntities e) {
		loaded = false;
		this.e = e;
		warp = null;
		warpIndex = -1;
		entryBox.removeAllItems();
		tgtZone.removeAllItems();
		if (e == null) {
			return;
		}
		for (int i = 0; i < mZonePnl.zones.length; i++) {
			tgtZone.addItem(i + " - " + LocationNames.getLocName(mZonePnl.zones[i].header.parentMap));
		}
		fillTransitionDropdown();
		reloadEntries(e.warpCount > 0 ? 0 : -1);
	}

	/**
	 * Rebuilds the dropdown from e.warps and selects one. Labels carry the warp
	 * number and its destination, so a save or a removal changes them.
	 */
	private void reloadEntries(int select) {
		loaded = false;
		entryBox.removeAllItems();
		for (int i = 0; i < e.warpCount; i++) {
			addNamedWarpEntry(e.warps.get(i), i);
		}
		loaded = true;
		entryBox.setSelectedIndex(select);
	}

	public void addNamedWarpEntry(ZoneEntities.Warp warp, int warpNumber) {
		entryBox.addItem(warpNumber + " - " + targetName(warp));
	}

	/**
	 * The destination as the dropdown names it. An unset warp says so, and a
	 * target past the end of the zone table - a zone removed from under it -
	 * is named rather than indexed, so the zone still opens and can be fixed.
	 */
	private String targetName(ZoneEntities.Warp warp) {
		if (warp.isUnset()) {
			return "<no destination>";
		}
		if (warp.targetZone >= mZonePnl.zones.length) {
			return "zone " + warp.targetZone + " (missing)";
		}
		return LocationNames.getLocName(mZonePnl.zones[warp.targetZone].header.parentMap);
	}

	public WarpEditForm() {
		initComponents();
		transition.setModel(transitionModel);
		setIntegerValueClass(new JFormattedTextField[]{x, y, z, w, h, tgtWarp});
	}

	public void setIntegerValueClass(JFormattedTextField[] fields) {
		for (int i = 0; i < fields.length; i++) {
			((NumberFormatter) fields[i].getFormatter()).setValueClass(Integer.class);
		}
	}

	public void showEntry(int index) {
		if (index == -1 || index >= e.warpCount) {
			return;
		}
		warpIndex = index;
		warp = e.warps.get(index);
		//nothing selected for a destination that is unset or no longer exists
		tgtZone.setSelectedIndex(warp.targetZone >= 0 && warp.targetZone < tgtZone.getItemCount() ? warp.targetZone : -1);
		tgtWarp.setValue(warp.targetWarpId == ZoneEntities.Warp.NO_TARGET ? null : warp.targetWarpId);
		warpType.setSelectedIndex(warp.directionality);
		posType.setSelectedIndex(warp.coordinateType);
		transition.setSelectedIndex(getTransitionIndex(warp.transitionType));
		x.setValue(warp.x);
		y.setValue(warp.y);
		z.setValue(warp.z);
		w.setValue(warp.w);
		h.setValue(warp.h);
		facedir.setSelectedIndex(warp.faceDirection);
	}

	/**
	 * The transition rows for the loaded game, in {@link WarpTransitions}'
	 * order - which is what makes {@link #getTransitionRaw} the row's code.
	 */
	public void fillTransitionDropdown() {
		transitionModel.removeAllElements();
		for (String label : WarpTransitions.labels(Workspace.isXY())) {
			transitionModel.addElement(label);
		}
	}

	/**
	 * The dropdown row that shows transition code {@code raw}, or -1 for a code
	 * the table has no name for. Both directions come off the one table; the
	 * form used to hold two switch statements that disagreed.
	 */
	public int getTransitionIndex(int raw) {
		return WarpTransitions.index(raw, Workspace.isXY());
	}

	/** The transition code row {@code index} writes, or -1 for no selection. */
	public int getTransitionRaw(int index) {
		return WarpTransitions.raw(index, Workspace.isXY());
	}

	/**
	 * The code a save writes when the dropdown is on {@code row} and the record
	 * currently wears {@code current}: the row's code, or - when nothing is
	 * selected, which is how a code the table has no name for is shown -
	 * the record's own code, kept. It used to write {@code getTransitionRaw(-1)},
	 * which is -1. Held here, apart from saveEntry, because saveEntry reaches
	 * the main window for its labels and a suite cannot call it.
	 */
	public int transitionToWrite(int row, int current) {
		return row == -1 ? current : getTransitionRaw(row);
	}

	public void saveEntry() {
		if (warp == null) {
			return;
		}
		ZoneEntities.Warp warp2 = new ZoneEntities.Warp();
		//no zone picked or no warp id typed leaves the warp unset, and the zone
		//refuses to save it; a stand-in destination would be a working door
		warp2.targetZone = tgtZone.getSelectedIndex() == -1 ? ZoneEntities.Warp.NO_TARGET : tgtZone.getSelectedIndex();
		warp2.targetWarpId = tgtWarp.getValue() == null ? ZoneEntities.Warp.NO_TARGET : (Integer) tgtWarp.getValue();
		warp2.directionality = warpType.getSelectedIndex();
		warp2.transitionType = transitionToWrite(transition.getSelectedIndex(), warp.transitionType);
		warp2.coordinateType = posType.getSelectedIndex();
		warp2.x = (Integer) x.getValue();
		warp2.y = (Integer) y.getValue();
		warp2.z = (Integer) z.getValue();
		warp2.w = (Integer) w.getValue();
		warp2.h = (Integer) h.getValue();
		warp2.faceDirection = facedir.getSelectedIndex();
		if (!warp.sameValues(warp2)) {
			e.warps.set(warpIndex, warp2);
			reloadEntries(warpIndex);
			e.modified = true;
		}
	}

	/**
	 * Adds a warp at a tile with no destination. It stays unset - and the zone
	 * refuses to save - until the user picks one. It used to be pointed back at
	 * the open zone as a placeholder, which is a working door that nothing
	 * could tell from a finished one.
	 */
	public void addEntry(Point tile) {
		ZoneEntities.Warp newWarp = new ZoneEntities.Warp();
		newWarp.x = tile.x * 18 + 9;
		newWarp.y = tile.y * 18 + 9;
		e.warps.add(newWarp);
		e.warpCount++;
		reloadEntries(e.warpCount - 1);
		e.modified = true;
	}

	public void removeEntry() {
		if (warp == null) {
			return;
		}
		int removed = warpIndex;
		e.warps.remove(warpIndex);
		e.warpCount--;
		warp = null;
		warpIndex = -1;
		reloadEntries(Math.min(removed, e.warpCount - 1));
		e.modified = true;
	}

	public void setWarp(int index) {
		entryBox.setSelectedIndex(index);
	}

	public void refresh() {
		entryBox.setSelectedIndex(entryBox.getSelectedIndex());
	}

	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        zLabel = new javax.swing.JLabel();
        y = new javax.swing.JFormattedTextField();
        yLabel = new javax.swing.JLabel();
        facedirLabel = new javax.swing.JLabel();
        wLabel = new javax.swing.JLabel();
        facedir = new javax.swing.JComboBox<>();
        w = new javax.swing.JFormattedTextField();
        entryBox = new javax.swing.JComboBox<>();
        h = new javax.swing.JFormattedTextField();
        tgtConfLabel = new javax.swing.JLabel();
        hLabel = new javax.swing.JLabel();
        tgtZoneLabel = new javax.swing.JLabel();
        transitionLabel = new javax.swing.JLabel();
        tgtZone = new javax.swing.JComboBox<>();
        tgtWarpIdLabel = new javax.swing.JLabel();
        tgtWarp = new javax.swing.JFormattedTextField();
        warpTypeLabel = new javax.swing.JLabel();
        warpType = new javax.swing.JComboBox<>();
        posSep = new javax.swing.JSeparator();
        posLabel = new javax.swing.JLabel();
        posTypeLabel = new javax.swing.JLabel();
        posType = new javax.swing.JComboBox<>();
        xLabel = new javax.swing.JLabel();
        x = new javax.swing.JFormattedTextField();
        z = new javax.swing.JFormattedTextField();
        btnSave = new javax.swing.JButton();
        btnAdd = new javax.swing.JButton();
        btnRemove = new javax.swing.JButton();
        transition = new javax.swing.JComboBox<>();

        zLabel.setText("Z");

        y.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        yLabel.setText("Y");

        facedirLabel.setText("Contact direction:");

        wLabel.setText("W");

        facedir.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Up", "Down", "Left", "Right" }));

        w.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        entryBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                entryBoxActionPerformed(evt);
            }
        });

        h.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        tgtConfLabel.setText("Target configuration:");

        hLabel.setText("H");

        tgtZoneLabel.setText("Target zone:");

        transitionLabel.setText("Transition type:");

        tgtWarpIdLabel.setText("Target warp ID:");

        tgtWarp.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        warpTypeLabel.setText("Warp type:");

        warpType.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Bidirectional", "Send only", "Receive only" }));

        posLabel.setText("Positioning:");

        posTypeLabel.setText("Type:");

        posType.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "X/Z/Y", "X/Y/-" }));

        xLabel.setText("X");

        x.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        z.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        btnAdd.setText("New entry");
        btnAdd.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddActionPerformed(evt);
            }
        });

        btnRemove.setText("Remove entry");
        btnRemove.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemoveActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(posSep)
                    .addComponent(entryBox, javax.swing.GroupLayout.Alignment.TRAILING, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(tgtConfLabel)
                            .addComponent(posLabel)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(posTypeLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(posType, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(transitionLabel)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(xLabel)
                                    .addComponent(zLabel)
                                    .addComponent(yLabel))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(layout.createSequentialGroup()
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addGroup(layout.createSequentialGroup()
                                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                                    .addComponent(x, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
                                                    .addComponent(z, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE))
                                                .addGap(18, 18, 18)
                                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                                    .addComponent(hLabel)
                                                    .addComponent(wLabel))
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED))
                                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                                .addComponent(facedirLabel)
                                                .addGap(32, 32, 32)))
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                            .addComponent(facedir, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                            .addComponent(h)
                                            .addComponent(w, javax.swing.GroupLayout.DEFAULT_SIZE, 73, Short.MAX_VALUE)))
                                    .addComponent(y, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE))))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(btnSave, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnAdd, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnRemove, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(tgtWarpIdLabel)
                            .addComponent(tgtZoneLabel)
                            .addComponent(warpTypeLabel))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(transition, javax.swing.GroupLayout.Alignment.TRAILING, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(tgtZone, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                    .addComponent(tgtWarp)
                                    .addComponent(warpType, 0, 109, Short.MAX_VALUE))
                                .addGap(0, 190, Short.MAX_VALUE)))))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(entryBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(tgtConfLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(tgtZone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(tgtZoneLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(tgtWarp, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(tgtWarpIdLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(warpTypeLabel)
                    .addComponent(warpType, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(transitionLabel)
                    .addComponent(transition, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(posSep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(posLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(posTypeLabel)
                    .addComponent(posType, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(wLabel)
                    .addComponent(w, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(xLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(z, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(h, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(hLabel)
                    .addComponent(zLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(yLabel))
                .addGap(18, 18, 18)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(facedir, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(facedirLabel))
                .addGap(18, 18, 18)
                .addComponent(btnAdd)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnRemove)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnSave)
                .addContainerGap(19, Short.MAX_VALUE))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void entryBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_entryBoxActionPerformed
		if (loaded && entryBox.getSelectedIndex() != -1) {
			showEntry(entryBox.getSelectedIndex());
		}
    }//GEN-LAST:event_entryBoxActionPerformed

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
		saveEntry();
		repaintFrame();
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnAddActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddActionPerformed
		if (e != null) {
			addEntry(mTileMapPanel.getTileAtViewportCentre());
			repaintFrame();
		}
    }//GEN-LAST:event_btnAddActionPerformed

    private void btnRemoveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemoveActionPerformed
		removeEntry();
		repaintFrame();
    }//GEN-LAST:event_btnRemoveActionPerformed

	/**
	 * Repaints the editor window, if there is one. Same accommodation
	 * NPCEditForm makes: the three buttons above are otherwise unreachable
	 * from a guard, because a headless run has no JFrame to repaint and
	 * frame.repaint() threw before any of them could be asked what they did.
	 */
	private void repaintFrame() {
		if (frame != null) {
			frame.repaint();
		}
	}


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnAdd;
    private javax.swing.JButton btnRemove;
    private javax.swing.JButton btnSave;
    private javax.swing.JComboBox<String> entryBox;
    private javax.swing.JComboBox<String> facedir;
    private javax.swing.JLabel facedirLabel;
    private javax.swing.JFormattedTextField h;
    private javax.swing.JLabel hLabel;
    private javax.swing.JLabel posLabel;
    private javax.swing.JSeparator posSep;
    private javax.swing.JComboBox<String> posType;
    private javax.swing.JLabel posTypeLabel;
    private javax.swing.JLabel tgtConfLabel;
    private javax.swing.JFormattedTextField tgtWarp;
    private javax.swing.JLabel tgtWarpIdLabel;
    private javax.swing.JComboBox<String> tgtZone;
    private javax.swing.JLabel tgtZoneLabel;
    private javax.swing.JComboBox<String> transition;
    private javax.swing.JLabel transitionLabel;
    private javax.swing.JFormattedTextField w;
    private javax.swing.JLabel wLabel;
    private javax.swing.JComboBox<String> warpType;
    private javax.swing.JLabel warpTypeLabel;
    private javax.swing.JFormattedTextField x;
    private javax.swing.JLabel xLabel;
    private javax.swing.JFormattedTextField y;
    private javax.swing.JLabel yLabel;
    private javax.swing.JFormattedTextField z;
    private javax.swing.JLabel zLabel;
    // End of variables declaration//GEN-END:variables
}
