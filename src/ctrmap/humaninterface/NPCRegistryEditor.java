package ctrmap.humaninterface;

import ctrmap.formats.npcreg.NPCRegistry;
import java.util.ArrayList;
import java.util.Map;
import javax.swing.DefaultListModel;
import javax.swing.JFormattedTextField;
import javax.swing.text.NumberFormatter;

/**
 * Regedit class for NPC registries, more comprehensible than PRE.
 */
public class NPCRegistryEditor extends ctrmap.humaninterface.FormPanel {

	private NPCRegistry reg;
	private NPCRegistry.NPCRegistryEntry e;
	private DefaultListModel<String> model = new DefaultListModel<>();
	private ArrayList<Integer> dict = new ArrayList<>();

	/**
	 * Creates new form NPCRegistryEditor
	 */
	public NPCRegistryEditor() {
		initComponents();
		Forms.bound(uid, 0, 0xFFFF); //unsigned 16-bit in the registry: -1 was saved as 0xFFFF (F124)
		Forms.bound(mdl, 0, 0xFFFF);
		setUnsignedByteValueClass(new JFormattedTextField[]{shadowType, areaW, areaH});
	}

	/**
	 * What this used to do in {@code windowClosing}, now that it is a pane.
	 *
	 * <p>The body is the one that was on the window, with two changes forced by
	 * there no longer being one: {@code dispose()} is gone, because leaving is the
	 * host's business, and the dialog owner is this panel rather than the event
	 * source. It answers false when a save was refused - on the window that was
	 * DO_NOTHING_ON_CLOSE, and dropping it would discard the edit silently.
	 */
	@Override
	public boolean closeRequested() {
		//what the save answered (F105): this said true over a refused save, so the pane went and the
		//edit with it
		return saveEntry(true);
	}

	public void setUnsignedByteValueClass(JFormattedTextField[] fields) {
		Forms.numberClass(Integer.class, fields);
		for (JFormattedTextField f : fields) {
			((NumberFormatter) f.getFormatter()).setMaximum(255);
		}
	}

	public void loadRegistry(NPCRegistry reg) {
		this.reg = reg;
		entryList.removeAll();
		model.removeAllElements();
		entryList.setModel(model);
		if (reg != null) {
			for (Map.Entry<Integer, NPCRegistry.NPCRegistryEntry> entry : reg.entries.entrySet()) {
				model.addElement("UID: " + entry.getValue().uid + " | Model: " + entry.getValue().model);
				dict.add(entry.getValue().uid);
			}
		}
		entryCountLabel.setText("Entry count: " + model.getSize());
		ctrmap.util.Selections.onSettled(entryList, () -> {
			if (entryList.getSelectedValue() == null || entryList.getSelectedIndex() == -1) {
				return;
			}
			saveEntry(false);
			showEntry(dict.get(entryList.getSelectedIndex()));
		});
	}

	public void setEntry(int id) {
		entryList.setSelectedIndex(dict.indexOf(id));
	}

	public void showEntry(int id) {
		if (reg == null || !reg.entries.containsKey(id)) {
			return;
		}
		NPCRegistry.NPCRegistryEntry entry = reg.entries.get(id);
		uid.setValue(entry.uid);
		mdl.setValue(entry.model);
		areaW.setValue(entry.collW);
		areaH.setValue(entry.collH);
		render.setSelected(entry.renderEnabled != 0);
		shadow.setSelected(entry.shadowEnabled != 0);
		shadowType.setValue(entry.shadowEnabled);
		item.setSelected(entry.isItOb == 0);
		dummy.setSelected(entry.isDummy == 1);
		e = entry;
		//THROUGH THE REGISTRY (census M.58): this decoded the MoveModels BCH itself, from the global workspace,
		//with none of the registry loader's guards - an entry with no mesh threw out of the list's listener - and
		//against the open game rather than the registry's own
		customH3DPreview1.loadModel(reg.loadFreshModel(entry.uid));
	}

	public boolean saveEntry(boolean dialog) {
		if (e != null) {
			int newUid = ((Number) uid.getValue()).intValue();
			if (newUid != e.uid && reg.entries.containsKey(newUid)) {
				ctrmap.Ui.error(this, "The specified UID is already registered. Please use another one.", "UID not unique");
				return false;
			}
			//EDITED ON A COPY (F081): rebuilt from defaults, every edit reset the eight fields this form
			//does not show
			NPCRegistry.NPCRegistryEntry e2 = e.copy();
			e2.uid = newUid;
			e2.model = ((Number) mdl.getValue()).intValue();
			if (render.isSelected()) {
				if (e.renderEnabled != 0) {
					e2.renderEnabled = e.renderEnabled;
				} else {
					e2.renderEnabled = 2;
				}
			} else {
				e2.renderEnabled = 0;
			}
			e2.shadowEnabled = ((Number) shadowType.getValue()).intValue();
			e2.isItOb = item.isSelected() ? 0 : 1;
			e2.isDummy = dummy.isSelected() ? 1 : 0;
			e2.collW = ((Number) areaW.getValue()).intValue();
			e2.collH = ((Number) areaH.getValue()).intValue();
			if (!e2.equals(e)) {
				//NO SHOWS THE ENTRY AS IT IS (F106): it left the boxes showing the edit it threw away
				switch (ctrmap.Ui.askToKeep(dialog, "NPC registry", () -> showEntry(e.uid))) {
					case DISCARD:
						return true;
					case CANCEL:
						return false;
				}
				model.setElementAt("UID: " + e2.uid + " | Model: " + e2.model, dict.indexOf(e.uid));
				dict.set(dict.indexOf(e.uid), e2.uid);
				//IN ITS OWN PLACE in the file, through the registry (F081)
				reg.replace(e.uid, e2);
				e = e2;
				reg.mapModel(e.uid, e.model);
				showEntry(e.uid);
			}
		}
		return true;
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        uidLabel = new javax.swing.JLabel();
        uid = new javax.swing.JSpinner();
        mdlLabel = new javax.swing.JLabel();
        mdl = new javax.swing.JSpinner();
        headerSep = new javax.swing.JSeparator();
        render = new javax.swing.JCheckBox();
        shadow = new javax.swing.JCheckBox();
        item = new javax.swing.JCheckBox();
        dummy = new javax.swing.JCheckBox();
        collLabel = new javax.swing.JLabel();
        shadowType = new javax.swing.JFormattedTextField();
        areaWLabel = new javax.swing.JLabel();
        areaHLabel = new javax.swing.JLabel();
        areaH = new javax.swing.JFormattedTextField();
        btnSave = new javax.swing.JButton();
        jScrollPane1 = new javax.swing.JScrollPane();
        entryList = new javax.swing.JList<>();
        listSep = new javax.swing.JSeparator();
        entryCountLabel = new javax.swing.JLabel();
        btnAdd = new javax.swing.JButton();
        btnRemove = new javax.swing.JButton();
        customH3DPreview1 = new ctrmap.humaninterface.CustomH3DPreview();
        areaW = new javax.swing.JFormattedTextField();

        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        setTitle("NRE");
        setLocationByPlatform(true);
        setMinimumSize(new java.awt.Dimension(255, 271));
        setResizable(false);

        uidLabel.setText("UID");

        uid.setModel(new javax.swing.SpinnerNumberModel(0, null, 65535, 1));

        mdlLabel.setText("Source file");

        mdl.setModel(new javax.swing.SpinnerNumberModel(0, null, 65535, 1));

        render.setText("Render this model");

        shadow.setText("Draw shadow sprite | Type:");
        shadow.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                shadowActionPerformed(evt);
            }
        });

        item.setText("This is an item/object");

        dummy.setText("This is a dummy model");

        collLabel.setText("Collision area:");

        shadowType.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        areaWLabel.setText("Width");

        areaHLabel.setText("Height");

        areaH.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        areaH.setPreferredSize(new java.awt.Dimension(75, 20));

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        jScrollPane1.setViewportView(entryList);

        listSep.setOrientation(javax.swing.SwingConstants.VERTICAL);

        entryCountLabel.setText("Entry count:");

        btnAdd.setText("Add");
        btnAdd.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddActionPerformed(evt);
            }
        });

        btnRemove.setText("Remove");
        btnRemove.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemoveActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout customH3DPreview1Layout = new javax.swing.GroupLayout(customH3DPreview1);
        customH3DPreview1.setLayout(customH3DPreview1Layout);
        customH3DPreview1Layout.setHorizontalGroup(
            customH3DPreview1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 189, Short.MAX_VALUE)
        );
        customH3DPreview1Layout.setVerticalGroup(
            customH3DPreview1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 160, Short.MAX_VALUE)
        );

        areaW.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(headerSep, javax.swing.GroupLayout.PREFERRED_SIZE, 241, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(layout.createSequentialGroup()
                                .addContainerGap()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(areaWLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(areaW, javax.swing.GroupLayout.PREFERRED_SIZE, 76, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(areaHLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(areaH, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(uidLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(uid, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(mdlLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(mdl, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                    .addComponent(dummy)
                                    .addComponent(item)
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(shadow)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(shadowType, javax.swing.GroupLayout.PREFERRED_SIZE, 40, javax.swing.GroupLayout.PREFERRED_SIZE))
                                    .addComponent(render)
                                    .addComponent(collLabel))
                                .addGap(8, 12, Short.MAX_VALUE)
                                .addComponent(listSep, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 205, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(entryCountLabel))))
                        .addComponent(customH3DPreview1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(btnSave, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnAdd, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnRemove, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(uidLabel)
                            .addComponent(uid, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(mdlLabel)
                            .addComponent(mdl, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(entryCountLabel))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(headerSep, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(2, 2, 2)
                        .addComponent(render)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(shadow)
                            .addComponent(shadowType, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(item)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(dummy)
                        .addGap(20, 20, 20)
                        .addComponent(collLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(areaWLabel)
                            .addComponent(areaH, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(areaHLabel)
                            .addComponent(areaW, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                        .addComponent(listSep, javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(layout.createSequentialGroup()
                            .addGap(35, 35, 35)
                            .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                .addComponent(customH3DPreview1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 160, javax.swing.GroupLayout.PREFERRED_SIZE)))))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnSave)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnAdd)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnRemove)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
		saveEntry(false);
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnAddActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddActionPerformed
		if (reg.entries.size() < NPCRegistry.MAX_ENTRIES) {
			NPCRegistry.NPCRegistryEntry e2 = new NPCRegistry.NPCRegistryEntry();
			//a FREE UID (F081): the default 0 replaced the UID-0 dummy retail registries carry, and the
			//model was never mapped, so nothing referencing the new UID could be drawn
			reg.register(e2, 1);
			try {
				reg.mapModel(e2.uid, e2.model);
			} catch (RuntimeException unmapped) {
				reg.remove(e2.uid);
				throw unmapped;
			}
			model.addElement("UID: " + e2.uid + " | Model: " + e2.model);
			dict.add(e2.uid);
			setEntry(e2.uid);
		} else {
			ctrmap.Ui.error(this, "An area can hold a maximum of " + NPCRegistry.MAX_ENTRIES + " NPCs.\n"
					+ "Reassign duplicates, free some space and try again.", "Registry limit error");
		}
    }//GEN-LAST:event_btnAddActionPerformed

    private void btnRemoveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemoveActionPerformed
		int index = entryList.getSelectedIndex();
		//NOTHING SELECTED REMOVES NOTHING (census M.77): -1 threw out of this listener and the button looked dead
		if (index < 0 || index >= dict.size()) {
			return;
		}
		e = null;   //and the entry removed is no longer the one shown: moving the selection saved it back
		reg.remove(dict.get(index));
		model.removeElementAt(index);
		dict.remove(index);
		if (index >= model.size()) {
			entryList.setSelectedIndex(index - 1);
		} else {
			entryList.setSelectedIndex(index);
		}
		reg.modified = true;
    }//GEN-LAST:event_btnRemoveActionPerformed

    private void shadowActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_shadowActionPerformed
		if (e == null) {
			return;   //no entry shown yet: nothing to set the shadow of (census L.44)
		}
		if (shadow.isSelected()) {
			if (e.shadowEnabled != 0) {
				shadowType.setValue(e.shadowEnabled);
			} else {
				shadowType.setValue(1);
			}
		} else {
			shadowType.setValue(0);
		}
    }//GEN-LAST:event_shadowActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JFormattedTextField areaH;
    private javax.swing.JLabel areaHLabel;
    private javax.swing.JFormattedTextField areaW;
    private javax.swing.JLabel areaWLabel;
    private javax.swing.JButton btnAdd;
    private javax.swing.JButton btnRemove;
    private javax.swing.JButton btnSave;
    private javax.swing.JLabel collLabel;
    private ctrmap.humaninterface.CustomH3DPreview customH3DPreview1;
    private javax.swing.JCheckBox dummy;
    private javax.swing.JLabel entryCountLabel;
    private javax.swing.JList<String> entryList;
    private javax.swing.JSeparator headerSep;
    private javax.swing.JCheckBox item;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JSeparator listSep;
    private javax.swing.JSpinner mdl;
    private javax.swing.JLabel mdlLabel;
    private javax.swing.JCheckBox render;
    private javax.swing.JCheckBox shadow;
    private javax.swing.JFormattedTextField shadowType;
    private javax.swing.JSpinner uid;
    private javax.swing.JLabel uidLabel;
    // End of variables declaration//GEN-END:variables
}
