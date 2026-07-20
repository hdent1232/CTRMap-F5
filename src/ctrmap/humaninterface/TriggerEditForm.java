package ctrmap.humaninterface;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.zone.ZoneEntities;
import java.awt.Point;
import java.util.ArrayList;
import javax.swing.JFormattedTextField;
import javax.swing.text.NumberFormatter;

/**
 * Editor form for zone script triggers (both interaction and step-on types).
 */
public class TriggerEditForm extends javax.swing.JPanel {

	public ZoneEntities e;
	public ZoneEntities.Trigger trigger;
	public boolean loaded = false;
	private boolean scriptDropdownLoading = false;

	public TriggerEditForm() {
		initComponents();
		setIntegerValueClass(new JFormattedTextField[]{script, u2, constant, u6, u8, x, y, w, h, u14, u16f});
	}

	public void setIntegerValueClass(JFormattedTextField[] fields) {
		for (int i = 0; i < fields.length; i++) {
			((NumberFormatter) fields[i].getFormatter()).setValueClass(Integer.class);
		}
	}

	public void loadFromEntities(ZoneEntities e) {
		loaded = false;
		this.e = e;
		trigger = null;
		entryBox.removeAllItems();
		populateScriptDropdown();
		if (e == null) {
			return;
		}
		for (int i = 0; i < currentList().size(); i++) {
			addNamedTriggerEntry(currentList().get(i), i);
		}
		loaded = true;
		if (entryBox.getItemCount() > 0) {
			showEntry(0);
		}
	}

	private ArrayList<ZoneEntities.Trigger> currentList() {
		return typeBox.getSelectedIndex() == 1 ? e.triggers2 : e.triggers1;
	}

	private void addNamedTriggerEntry(ZoneEntities.Trigger t, int n) {
		entryBox.addItem(n + " - script " + t.script);
	}

	private void populateScriptDropdown() {
		scriptDropdownLoading = true;
		scriptDropdown.removeAllItems();
		if (ctrmap.Workspace.valid && mZonePnl != null && mZonePnl.zone != null && mZonePnl.zone.s != null) {
			mZonePnl.zone.s.decompressThis();
			for (String item : TalkerScriptWizard.buildScriptIdItems(mZonePnl.zone.s)) {
				scriptDropdown.addItem(item);
			}
		}
		scriptDropdown.setSelectedIndex(-1);
		scriptDropdownLoading = false;
	}

	private void syncScriptDropdown(int scriptId) {
		scriptDropdownLoading = true;
		int match = -1;
		for (int i = 0; i < scriptDropdown.getItemCount(); i++) {
			if (parseLeadingInt(scriptDropdown.getItemAt(i)) == scriptId) {
				match = i;
				break;
			}
		}
		scriptDropdown.setSelectedIndex(match);
		scriptDropdownLoading = false;
	}

	private static int parseLeadingInt(String item) {
		try {
			int space = item.indexOf(' ');
			return Integer.parseInt(space == -1 ? item : item.substring(0, space));
		} catch (NumberFormatException ex) {
			return Integer.MIN_VALUE;
		}
	}

	public void showEntry(int index) {
		if (index == -1 || index >= currentList().size()) {
			return;
		}
		trigger = currentList().get(index);
		script.setValue(trigger.script);
		syncScriptDropdown(trigger.script);
		u2.setValue(trigger.u2);
		constant.setValue(trigger.constant);
		u6.setValue(trigger.u6);
		u8.setValue(trigger.u8);
		x.setValue(trigger.x);
		y.setValue(trigger.y);
		w.setValue(trigger.w);
		h.setValue(trigger.h);
		u14.setValue(trigger.u14);
		u16f.setValue(trigger.u16val);
	}

	public void saveEntry() {
		if (trigger == null) {
			return;
		}
		ZoneEntities.Trigger t2 = new ZoneEntities.Trigger();
		t2.script = (Integer) script.getValue();
		t2.u2 = (Integer) u2.getValue();
		t2.constant = (Integer) constant.getValue();
		t2.u6 = (Integer) u6.getValue();
		t2.u8 = (Integer) u8.getValue();
		t2.uA = trigger.uA;
		t2.x = (Integer) x.getValue();
		t2.y = (Integer) y.getValue();
		t2.w = (Integer) w.getValue();
		t2.h = (Integer) h.getValue();
		t2.u14 = (Integer) u14.getValue();
		t2.u16val = (Integer) u16f.getValue();
		if (!trigger.equals(t2)) {
			//locate by the combo box index - identical triggers would make indexOf() hit the wrong element
			int idx = entryBox.getSelectedIndex();
			if (idx == -1 || idx >= currentList().size() || currentList().get(idx) != trigger) {
				return; //entry no longer exists (e.g. removed); nothing to overwrite
			}
			trigger = t2;
			currentList().set(idx, trigger);
			e.modified = true;
		}
	}

	public void setTrigger(int index) {
		entryBox.setSelectedIndex(index);
	}

	public void selectTrigger(int type, int index) {
		if (typeBox.getSelectedIndex() != type) {
			typeBox.setSelectedIndex(type);
		}
		setTrigger(index);
	}

	public void refresh() {
		entryBox.setSelectedIndex(entryBox.getSelectedIndex());
	}

	private void initComponents() {

		entryBox = new javax.swing.JComboBox<>();
		typeLabel = new javax.swing.JLabel();
		typeBox = new javax.swing.JComboBox<>();
		scriptLabel = new javax.swing.JLabel();
		script = new javax.swing.JFormattedTextField();
		scriptDropdown = new javax.swing.JComboBox<>();
		u2Label = new javax.swing.JLabel();
		u2 = new javax.swing.JFormattedTextField();
		constantLabel = new javax.swing.JLabel();
		constant = new javax.swing.JFormattedTextField();
		u6Label = new javax.swing.JLabel();
		u6 = new javax.swing.JFormattedTextField();
		u8Label = new javax.swing.JLabel();
		u8 = new javax.swing.JFormattedTextField();
		posSep = new javax.swing.JSeparator();
		posLabel = new javax.swing.JLabel();
		xLabel = new javax.swing.JLabel();
		x = new javax.swing.JFormattedTextField();
		yLabel = new javax.swing.JLabel();
		y = new javax.swing.JFormattedTextField();
		wLabel = new javax.swing.JLabel();
		w = new javax.swing.JFormattedTextField();
		hLabel = new javax.swing.JLabel();
		h = new javax.swing.JFormattedTextField();
		u14Label = new javax.swing.JLabel();
		u14 = new javax.swing.JFormattedTextField();
		u16Label = new javax.swing.JLabel();
		u16f = new javax.swing.JFormattedTextField();
		btnAdd = new javax.swing.JButton();
		btnRemove = new javax.swing.JButton();
		btnSave = new javax.swing.JButton();

		typeLabel.setText("Type:");

		typeBox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Type 1 (interaction)", "Type 2 (step-on)" }));

		scriptLabel.setText("Script:");
		u2Label.setText("Unknown 2:");
		constantLabel.setText("Constant:");
		u6Label.setText("Unknown 6:");
		u8Label.setText("Unknown 8:");

		posLabel.setText("Positioning (tiles):");

		xLabel.setText("X");
		yLabel.setText("Y");
		wLabel.setText("W");
		hLabel.setText("H");
		u14Label.setText("Unknown 14:");
		u16Label.setText("Unknown 16:");

		script.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		u2.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		constant.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		u6.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		u8.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		x.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		y.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		w.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		h.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		u14.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
		u16f.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

		entryBox.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				entryBoxActionPerformed(evt);
			}
		});

		typeBox.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				typeBoxActionPerformed(evt);
			}
		});

		scriptDropdown.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				scriptDropdownActionPerformed(evt);
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

		btnSave.setText("Save");
		btnSave.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				btnSaveActionPerformed(evt);
			}
		});

		javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
		this.setLayout(layout);
		layout.setHorizontalGroup(
			layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
			.addGroup(layout.createSequentialGroup()
				.addContainerGap()
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
					.addComponent(entryBox, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
					.addComponent(posSep)
					.addComponent(posLabel)
					.addGroup(layout.createSequentialGroup()
						.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
							.addComponent(typeLabel)
							.addComponent(scriptLabel)
							.addComponent(u2Label)
							.addComponent(constantLabel)
							.addComponent(u6Label)
							.addComponent(u8Label)
							.addComponent(xLabel)
							.addComponent(yLabel)
							.addComponent(u14Label))
						.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
						.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
							.addComponent(typeBox, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
							.addGroup(layout.createSequentialGroup()
								.addComponent(script, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
								.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
								.addComponent(scriptDropdown, javax.swing.GroupLayout.PREFERRED_SIZE, 130, javax.swing.GroupLayout.PREFERRED_SIZE))
							.addComponent(u2, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
							.addComponent(constant, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
							.addComponent(u6, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
							.addComponent(u8, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
							.addComponent(u14, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
							.addGroup(layout.createSequentialGroup()
								.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
									.addComponent(x, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
									.addComponent(y, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE))
								.addGap(18, 18, 18)
								.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
									.addComponent(wLabel)
									.addComponent(hLabel))
								.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
								.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
									.addComponent(w, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)
									.addComponent(h, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE)))
							.addGroup(layout.createSequentialGroup()
								.addComponent(u16Label)
								.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
								.addComponent(u16f, javax.swing.GroupLayout.PREFERRED_SIZE, 73, javax.swing.GroupLayout.PREFERRED_SIZE))))
					.addComponent(btnAdd, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
					.addComponent(btnRemove, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
					.addComponent(btnSave, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
				.addContainerGap())
		);
		layout.setVerticalGroup(
			layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
			.addGroup(layout.createSequentialGroup()
				.addContainerGap()
				.addComponent(entryBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(typeLabel)
					.addComponent(typeBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(scriptLabel)
					.addComponent(script, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
					.addComponent(scriptDropdown, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(u2Label)
					.addComponent(u2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(constantLabel)
					.addComponent(constant, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(u6Label)
					.addComponent(u6, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(u8Label)
					.addComponent(u8, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addComponent(posSep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addComponent(posLabel)
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(xLabel)
					.addComponent(x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
					.addComponent(wLabel)
					.addComponent(w, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(yLabel)
					.addComponent(y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
					.addComponent(hLabel)
					.addComponent(h, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
					.addComponent(u14Label)
					.addComponent(u14, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
					.addComponent(u16Label)
					.addComponent(u16f, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
				.addGap(18, 18, 18)
				.addComponent(btnAdd)
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addComponent(btnRemove)
				.addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
				.addComponent(btnSave)
				.addContainerGap(19, Short.MAX_VALUE))
		);
	}

	private void entryBoxActionPerformed(java.awt.event.ActionEvent evt) {
		if (loaded && entryBox.getSelectedIndex() != -1) {
			showEntry(entryBox.getSelectedIndex());
		}
	}

	private void scriptDropdownActionPerformed(java.awt.event.ActionEvent evt) {
		if (loaded && !scriptDropdownLoading && scriptDropdown.getSelectedIndex() != -1) {
			int id = parseLeadingInt((String) scriptDropdown.getSelectedItem());
			if (id != Integer.MIN_VALUE) {
				script.setValue(id);
			}
		}
	}

	private void typeBoxActionPerformed(java.awt.event.ActionEvent evt) {
		if (e != null) {
			loaded = false;
			trigger = null;
			entryBox.removeAllItems();
			for (int i = 0; i < currentList().size(); i++) {
				addNamedTriggerEntry(currentList().get(i), i);
			}
			loaded = true;
			if (entryBox.getItemCount() > 0) {
				showEntry(0);
			}
			frame.repaint();
		}
	}

	private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {
		saveEntry();
		frame.repaint();
	}

	private void btnAddActionPerformed(java.awt.event.ActionEvent evt) {
		if (e != null) {
			ZoneEntities.Trigger t = new ZoneEntities.Trigger();
			Point defaultPos = mTileMapPanel.getTileAtViewportCentre();
			t.x = defaultPos.x;
			t.y = defaultPos.y;
			currentList().add(t);
			if (typeBox.getSelectedIndex() == 1) {
				e.trigger2Count++;
			} else {
				e.trigger1Count++;
			}
			addNamedTriggerEntry(t, currentList().size() - 1);
			setTrigger(entryBox.getItemCount() - 1);
			frame.repaint();
			e.modified = true;
		}
	}

	private void btnRemoveActionPerformed(java.awt.event.ActionEvent evt) {
		if (e != null && trigger != null) {
			//remove by index - remove(Object) would delete the first field-identical trigger instead
			int idx = entryBox.getSelectedIndex();
			if (idx == -1 || idx >= currentList().size() || currentList().get(idx) != trigger) {
				return;
			}
			currentList().remove(idx);
			if (typeBox.getSelectedIndex() == 1) {
				e.trigger2Count--;
			} else {
				e.trigger1Count--;
			}
			entryBox.removeItemAt(entryBox.getSelectedIndex());
			if (entryBox.getItemCount() == 0) {
				trigger = null;
			} else if (entryBox.getSelectedIndex() >= entryBox.getItemCount()) {
				entryBox.setSelectedIndex(entryBox.getSelectedIndex() - 1);
			} else {
				entryBox.setSelectedIndex(entryBox.getSelectedIndex());
			}
			frame.repaint();
			e.modified = true;
		}
	}

	private javax.swing.JButton btnAdd;
	private javax.swing.JButton btnRemove;
	private javax.swing.JButton btnSave;
	private javax.swing.JFormattedTextField constant;
	private javax.swing.JLabel constantLabel;
	private javax.swing.JComboBox<String> entryBox;
	private javax.swing.JFormattedTextField h;
	private javax.swing.JLabel hLabel;
	private javax.swing.JLabel posLabel;
	private javax.swing.JSeparator posSep;
	private javax.swing.JFormattedTextField script;
	private javax.swing.JComboBox<String> scriptDropdown;
	private javax.swing.JLabel scriptLabel;
	private javax.swing.JComboBox<String> typeBox;
	private javax.swing.JLabel typeLabel;
	private javax.swing.JFormattedTextField u14;
	private javax.swing.JLabel u14Label;
	private javax.swing.JFormattedTextField u16f;
	private javax.swing.JLabel u16Label;
	private javax.swing.JFormattedTextField u2;
	private javax.swing.JLabel u2Label;
	private javax.swing.JFormattedTextField u6;
	private javax.swing.JLabel u6Label;
	private javax.swing.JFormattedTextField u8;
	private javax.swing.JLabel u8Label;
	private javax.swing.JFormattedTextField w;
	private javax.swing.JLabel wLabel;
	private javax.swing.JFormattedTextField x;
	private javax.swing.JLabel xLabel;
	private javax.swing.JFormattedTextField y;
	private javax.swing.JLabel yLabel;
}
