package ctrmap.humaninterface;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnAssembly;
import ctrmap.formats.scripts.PawnDisassembler;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnPrefixEntry;
import ctrmap.formats.scripts.PawnSubroutine;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultListModel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DocumentFilter;
import javax.swing.text.SimpleAttributeSet;

public class ScriptEditor extends javax.swing.JPanel implements ScriptView {

	public GFLPawnScript script;
	public ScriptDocumentListener sdl;
	public ScriptDocumentFilter sdf;
	private JTextArea ptrs = new JTextArea();
	private DefaultListModel prefixEntryList = new DefaultListModel();
	private List<PawnPrefixEntry> currentEntries;
	private PawnPrefixEntry currentEntry;
	private int currentIdx = -1;

	private String textSwapBuffer;
	public boolean editData = false;

	public boolean loaded = false;

	/** Writing the open zone back, handed in: the Save button does exactly that. */
	private final ZoneSaver zoneSaver;

	/** What the last live edit did, under the editor - see {@link #withStatus} (F242). */
	private final javax.swing.JLabel status = new javax.swing.JLabel(" ");

	/**
	 * What the party-parameter call under the caret passes, named (F052): the selector table was
	 * decoded from the executable and could only be read in the Java source. Shown here, not
	 * written into the text, which the assembler reads back.
	 */
	private final javax.swing.JLabel partyParam = new javax.swing.JLabel(" ");

	/**
	 * This editor with a line under it saying what the last edit did - built here rather than in
	 * the generated layout, which is the form designer's. An argument the editor could not take
	 * left the typed text on screen and the instruction as it was, without a word (F242). Under
	 * it, the party-parameter call the caret is on, named (F052).
	 */
	public javax.swing.JComponent withStatus() {
		javax.swing.JPanel box = new javax.swing.JPanel(new java.awt.BorderLayout());
		box.add(this, java.awt.BorderLayout.CENTER);
		javax.swing.JPanel lines = new javax.swing.JPanel(new java.awt.GridLayout(0, 1));
		lines.add(status);
		lines.add(partyParam);
		box.add(lines, java.awt.BorderLayout.SOUTH);
		return box;
	}

	/** The status line's text now. */
	public String status() {
		return status.getText();
	}

	/** The party-parameter line's text now. */
	public String partyParamLine() {
		return partyParam.getText();
	}

	/**
	 * Names the PokePartyGetParam / PokePartySetParam call the caret is on - its SYSREQ_N or one
	 * of the pushes feeding it - and clears the line anywhere else.
	 */
	void showPartyParam() {
		String said = " ";
		if (script != null && !editData) {
			try {
				int line = disassemblyArea.getLineOfOffset(disassemblyArea.getCaretPosition());
				for (int i = 0; i < script.instructions.size(); i++) {
					if (script.instructions.get(i).line == line) {
						ctrmap.formats.scripts.PartyParamCall call = ctrmap.formats.scripts.PartyParamCall.at(script, i);
						if (call != null) {
							said = call.describe();
						}
						break;
					}
				}
			} catch (BadLocationException ex) {
				said = " "; //a caret past the text names nothing
			}
		}
		partyParam.setText(said);
	}

	/** Says whether the line's arguments were taken, and why not when not; true when taken. */
	public boolean sayTaken(int line, String notTaken) {
		status.setText(notTaken.isEmpty() ? " " : "Line " + (line + 1) + " not taken: " + notTaken
				+ " - the instruction is as it was.");
		return notTaken.isEmpty();
	}

	/**
	 * Creates new form ScriptEditor
	 */
	public ScriptEditor(ZoneSaver zoneSaver) {
		if (zoneSaver == null) {
			throw new IllegalArgumentException("ScriptEditor must be handed the zone saver its Save button uses");
		}
		this.zoneSaver = zoneSaver;
		initComponents();
		ptrs.setColumns(6);
		ptrs.setFont(new java.awt.Font("Monospaced", 0, 14)); // NOI18N
		ptrs.setRows(5);
		ptrs.setBackground(new Color(255, 255, 170));
		ptrs.setMinimumSize(new Dimension(30, 720));
		ptrs.setBorder(new MatteBorder(0, 0, 0, 5, Color.GRAY));
		ptrs.setEditable(false);
		disassemblyScrollPane.setViewportView(disassemblyArea);
		prefixItemList.setModel(prefixEntryList);
		ctrmap.util.Selections.onSettled(prefixItemList, () -> {
			if (loaded) {
				savePrefixEntry();
				showPrefixEntry(prefixItemList.getSelectedIndex());
			}
		});
		sdl = new ScriptDocumentListener(this);
		sdf = new ScriptDocumentFilter(this);
		assemblerOutput.setLineWrap(true);
		assemblerOutput.setWrapStyleWord(true);
		disassemblyArea.getDocument().addDocumentListener(sdl);
		disassemblyScrollPane.setRowHeaderView(ptrs);
		((AbstractDocument) disassemblyArea.getDocument()).setDocumentFilter(sdf);
		((DefaultCaret) disassemblyArea.getCaret()).setUpdatePolicy(DefaultCaret.UPDATE_WHEN_ON_EDT);
		disassemblyArea.addCaretListener(e -> showPartyParam());
	}

	@Override
	public void showScript(GFLPawnScript script) {
		loadScript(script);
	}

	/**
	 * Drops the script of a zone that is no longer open (F222): the text is emptied and read-only
	 * until the next zone's script is loaded. A failed load, a failed workspace open and Clean
	 * workspace all left the closed zone's script here, in a tab that looked live; its edits change
	 * the script object only the zone save writes, and that zone was gone, so every keystroke was
	 * lost without a word.
	 */
	public void unload() {
		loaded = false;
		script = null;
		sdl.setScript(null);
		sdf.setScript(null);
		disassemblyArea.setText("");
		disassemblyArea.setEditable(false);
	}

	public void loadScript(GFLPawnScript scr) {
		loaded = false;
		disassemblyArea.setEditable(true);
		script = scr;
		currentIdx = -1;
		sdl.setScript(scr);
		sdf.setScript(scr);
		script.setInstructionListeners();
		prefixCat.setSelectedIndex(1);
		textSwapBuffer = getDataText(scr.data);
		editData = false;
		btnIsDataEdit.setSelected(false);
		btnIsDataEdit.setText("Edit data");
		try {
			updateDocument(false, CaretMotion.NONE);
		} catch (BadLocationException ex) {
			Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
		}
		loaded = true;
	}

	public enum CaretMotion {
		NONE,
		FORWARD,
		BACKWARD
	}

	public static class ScriptDocumentFilter extends DocumentFilter {

		private ScriptEditor se;
		private GFLPawnScript scr;

		public ScriptDocumentFilter(ScriptEditor se) {
			this.se = se;
		}

		public void setScript(GFLPawnScript scr) {
			this.scr = scr;
		}

		@Override
		public void remove(DocumentFilter.FilterBypass fb, int offset, int length) {
			try {
				if (scr != null && se.loaded && !se.editData) {
					int off = offset;
					int len = length;
					String text = se.disassemblyArea.getText();
					String textToBeRemoved = text.substring(offset, offset + length);
					boolean isCtbl = false;
					int chara = off;
					while (chara > 0) {
						char test = text.charAt(chara);
						if (test != '\t') {
							chara--;
							if (test == ')') {
								break;
							}
						} else {
							if (text.charAt(chara - 1) == '\t') {
								isCtbl = true;
							}
							break;
						}
					}
					if (textToBeRemoved.contains("\n")) { //Only perform when an instruction has been actually removed, not just typo correcting and stuff
						int changedLineStart = getCountOfLineEndsInString(text, off);
						int changedLineEnd = getCountOfLineEndsInString(text, off + len);
						//int addedLines = changedLineEnd - changedLineStart;
						String newText = text.substring(0, offset) + text.substring(offset + length);
						List<PawnSubroutine> reassembled = PawnDisassembler.assembleScript(newText, scr).subroutines;
						int insCount = 0;
						for (PawnSubroutine s : reassembled) {
							insCount += s.instructions.size();
						}
						setLines(PawnDisassembler.disassembleScript(scr));
						List<PawnInstruction> removedInstructions = new ArrayList<>();
						int remCount = scr.instructions.size() - insCount;
						if (insCount < scr.instructions.size()) {
							for (PawnInstruction i : scr.instructions) {
								if ((remCount == 1 ? i.line > changedLineStart : i.line >= changedLineStart) && i.line <= changedLineEnd) {
									removedInstructions.add(i);
								}
							}
							scr.instructions.removeAll(removedInstructions);
							se.updateDocument(false, CaretMotion.BACKWARD);
						} else if (isCtbl) {
							setLines(reassembled);
							for (PawnSubroutine s : reassembled) {
								for (PawnInstruction i : s.instructions) {
									if (i.getCommand() == 0x82) {
										for (int linecheck = 0; linecheck < (i.argumentCount / 2 + 2); linecheck++) { //additional two lines for brackets
											if (i.line + linecheck >= changedLineStart && i.line + linecheck <= changedLineEnd) {
												//get entire casetbl for assembly
												int idx = 0;
												int endlinecnt = 0;
												while (idx < text.length() && endlinecnt < i.line) {
													if (text.charAt(idx) == '\n') {
														endlinecnt++;
													}
													idx++;
												}
												int closingBkt = text.indexOf("}", idx);
												String caseblk = text.substring(idx, closingBkt + 1);
												PawnInstruction newIns = PawnSubroutine.caseTblFromString(i.pointer, new PawnAssembly(caseblk));
												PawnInstruction replace = scr.lookupInstructionByPtr(i.pointer);
												if (replace.argumentCount > newIns.argumentCount) {
													replace.argumentCells = newIns.argumentCells;
													replace.argumentCount = newIns.argumentCount;
													se.updateDocument(false, CaretMotion.NONE);
												}
												break;
											}
										}
									}
								}
							}
						}
					}

				}
				super.remove(fb, offset, length);
			} catch (BadLocationException ex) {
				Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}

	public static class ScriptDocumentListener implements DocumentListener {

		private GFLPawnScript scr;
		private ScriptEditor se;

		public ScriptDocumentListener(ScriptEditor se) {
			this.se = se;
		}

		public void setScript(GFLPawnScript scr) {
			this.scr = scr;
		}

		/**
		 * Where {@code toFind} starts, scanning back from {@code pos}, or -1 when {@code stopAt} comes
		 * first or the text runs out. ONE LOOP FOR BOTH CASES (F109): the case-sensitive copy tested its
		 * stop marker with {@code &&} where {@code ||} belonged, so it never stopped and a '{' typed for
		 * a CASETBL in any subroutine but the first got the SUBROUTINE template; the insensitive copy
		 * took a substring past the end of the text and threw; neither looked at index 0.
		 */
		static int scanBackwardsFindUntil(String text, int pos, String toFind, String stopAt, boolean caseSensitive) {
			for (int idx = Math.min(pos, text.length() - 1); idx >= 0; idx--) {
				if (text.regionMatches(!caseSensitive, idx, toFind, 0, toFind.length())) {
					return idx;
				}
				if (text.regionMatches(!caseSensitive, idx, stopAt, 0, stopAt.length())) {
					return -1;
				}
			}
			return -1;
		}

		@Override
		public void insertUpdate(DocumentEvent e) {
			if (scr != null && se.loaded && !se.editData) {
				try {
					final int off = e.getOffset();
					final int len = e.getLength();
					String text = e.getDocument().getText(0, e.getDocument().getLength());
					if (text.substring(off, off + len).equals("{")) {
						//scan if sub or casetbl
						boolean movedToItsOwnLine = off > 0 && text.charAt(off - 1) != '\n';
						if (movedToItsOwnLine) {
							SwingUtilities.invokeLater(() -> {
								try {
									e.getDocument().insertString(off, "\n", new SimpleAttributeSet());
								} catch (BadLocationException ex) {
									Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
								}
							});
						}
						//JUST AFTER THE BRACE, wherever it ends up (2026-10-15): this was off + 2, which counted the
						//newline put before it - a brace typed at the start of a line has none, and its template was
						//inserted past the end of the text, refused, and lost
						final int afterBrace = off + 1 + (movedToItsOwnLine ? 1 : 0);
						if (scanBackwardsFindUntil(text, off - 1, "sub_", "\t", true) != -1) {
							SwingUtilities.invokeLater(() -> {
								try {
									e.getDocument().insertString(afterBrace, "\n\tRETN(0)\n}", new SimpleAttributeSet());
								} catch (BadLocationException ex) {
									Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
								}
							});
						} else {
							int idx = off - 1;
							while (idx > 0 && (text.charAt(idx) == '\n' || text.charAt(idx) == '\t')) {
								idx--;
							}
							if (scanBackwardsFindUntil(text, idx, "CASETBL", "\t", false) != -1) {
								SwingUtilities.invokeLater(() -> {
									try {
										e.getDocument().insertString(afterBrace, "\n\t\t* => 0x0\n\t}", new SimpleAttributeSet());
									} catch (BadLocationException ex) {
										Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
									}
								});
							}
						}
					}
					if (text.substring(off, off + len).equals("\n")) {
						int pos = off;
						StringBuilder tabBldr = new StringBuilder();
						if (off > 0 && (text.charAt(off - 1) == '{' || (off > 2 && text.substring(off - 2, off).equals("\t}")))) {
							tabBldr.append('\t'); //sub beginning
						} else {
							while (pos > 0) {
								if (text.charAt(pos) == '\t') {
									while (pos > 0 && text.charAt(pos) == '\t') {
										tabBldr.append('\t');
										pos--;
									}
									break;
								} else if (text.charAt((pos)) == '{' || text.charAt(pos) == '}') {
									//beginning/end of subroutine
									break;
								}
								pos--;
							}
						}
						SwingUtilities.invokeLater(() -> {
							try {
								e.getDocument().insertString(off + 1, tabBldr.toString(), new SimpleAttributeSet());
							} catch (BadLocationException ex) {
								Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
							}
						});
					}
					int changedLineStart = getCountOfLineEndsInString(text, off);
					int changedLineEnd = getCountOfLineEndsInString(text, off + len);
					StringBuilder changedLine = new StringBuilder();
					int chara = off;
					boolean isArgs = false;
					while (chara > 0) {
						char test = text.charAt(chara);
						if (test != '(' && !(test == '=' && text.charAt(chara + 1) == '>')) {
							chara--;
							if (test == ')' || test == '\t' || test == '\n') {
								break;
							}
						} else {
							isArgs = true;
							while (test != ')' && test != '\t' && test != '\n') {
								test = text.charAt(chara);
								changedLine.append(test);
								chara++;
							}
							break;
						}
					}

					List<PawnSubroutine> reassembled = PawnDisassembler.assembleScript(text, scr).subroutines;
					int insCount = 0;
					for (PawnSubroutine s : reassembled) {
						insCount += s.instructions.size();
					}
					if (insCount > scr.instructions.size()) {
						//AN INSERT RENUMBERS: refused, before anything changes, when a branch cannot follow (F086)
						String unrenumberable = scr.whyNotRenumberable();
						if (!unrenumberable.isEmpty()) {
							ctrmap.Ui.error(se, "Not inserted. " + unrenumberable, "Script editor");
							return;
						}
						setLines(reassembled);
						List<PawnInstruction> addedInstructions = new ArrayList<>();
						for (PawnSubroutine s : reassembled) {
							for (PawnInstruction i : s.instructions) {
								if (i.line >= changedLineStart && i.line <= changedLineEnd) {
									addedInstructions.add(i);
								} else if (i.getCommand() == 0x82) {
									for (int linecheck = 0; linecheck < (i.argumentCount / 2 + 1); linecheck++) { //additional two lines for brackets
										if (i.line + linecheck >= changedLineStart && i.line + linecheck <= changedLineEnd) {
											addedInstructions.add(i);
											break;
										}
									}
								}
							}
						}
						boolean added = false;
						for (int i = 0; i < scr.instructions.size(); i++) {
							PawnInstruction ins = scr.instructions.get(i);
							if (ins.line >= changedLineStart) {
								int idx = 0;
								for (PawnInstruction a : addedInstructions) {
									if (a.getCommand() == 0x82) {
										scr.instructions.add(i - 1 + idx, a);
									} else {
										scr.instructions.add(i + idx, a);
									}
									idx++;
								}
								added = true;
								break;
							}
						}
						if (!added) {
							//no instruction found after new ones, means they are at the end of script, so let's just append them
							scr.instructions.addAll(addedInstructions);
						}
						se.updateDocument(true, CaretMotion.FORWARD);
					} else if (isArgs) {
						setLines(reassembled);
						Outer:
						for (PawnSubroutine s : reassembled) {
							for (PawnInstruction i : s.instructions) {
								if (i.getCommand() == 0x82) {
									for (int linecheck = 0; linecheck < (i.argumentCount / 2 + 2); linecheck++) { //additional two lines for brackets
										if (i.line + linecheck >= changedLineStart && i.line + linecheck <= changedLineEnd) {
											//get entire casetbl for assembly
											int idx = 0;
											int endlinecnt = 0;
											while (idx < text.length() && endlinecnt < i.line) {
												if (text.charAt(idx) == '\n') {
													endlinecnt++;
												}
												idx++;
											}
											int closingBkt = text.indexOf("}", idx);
											String caseblk = text.substring(idx, closingBkt + 1);
											PawnInstruction newIns = PawnSubroutine.caseTblFromString(i.pointer, new PawnAssembly(caseblk));
											PawnInstruction replace = scr.lookupInstructionByPtr(i.pointer);
											if (replace.argumentCount < newIns.argumentCount) {
												replace.argumentCells = newIns.argumentCells;
												replace.argumentCount = newIns.argumentCount;
												se.updateDocument(true, CaretMotion.FORWARD);
											}
											break;
										}
									}
								} else if (i.line >= changedLineStart && i.line <= changedLineEnd) {
									se.sayTaken(i.line, scr.lookupInstructionByPtr(i.pointer).takeArgs(changedLine.toString()));
									break Outer;
								}
							}
						}
					}
				} catch (BadLocationException ex) {
					Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
		}

		@Override
		public void removeUpdate(DocumentEvent e) {
			try {
				if (!se.editData) {
					int off = e.getOffset();
					int len = e.getLength();
					String text = e.getDocument().getText(0, e.getDocument().getLength());
					int chara = Math.min(off, text.length() - 1);
					boolean isArgs = false;
					StringBuilder changedLine = new StringBuilder();
					while (chara > 0) {
						char test = text.charAt(chara);
						if (test != '(' && !(test == '=' && text.charAt(chara + 1) == '>')) {
							chara--;
							if (test == ')' || test == '\t' || test == '\n') {
								break;
							}
						} else {
							isArgs = true;
							while (test != ')' && test != '\t' && test != '\n') {
								test = text.charAt(chara);
								changedLine.append(test);
								chara++;
							}
							break;
						}
					}
					if (isArgs) {
						int changedLineStart = getCountOfLineEndsInString(text, off);
						int changedLineEnd = getCountOfLineEndsInString(text, off + len);
						for (PawnInstruction i : scr.instructions) {
							if (i.line >= changedLineStart && i.line <= changedLineEnd) {
								boolean success = se.sayTaken(i.line, scr.lookupInstructionByPtr(i.pointer).takeArgs(changedLine.toString()));
								if (success) {
									se.updateDocument(false, CaretMotion.BACKWARD);
								}
								break;
							}
						}
					}
				}
			} catch (BadLocationException ex) {
				Logger.getLogger(ScriptEditor.class.getName()).log(Level.SEVERE, null, ex);
			}
		}

		@Override
		public void changedUpdate(DocumentEvent e) {
		}
	}

	/** The renumbering now lives with the script it renumbers: {@link GFLPawnScript#setPtrsByIndex}. */
	public static void setPtrsByIndex(List<PawnInstruction> instructions) {
		GFLPawnScript.setPtrsByIndex(instructions);
	}

	public static void setLines(List<PawnSubroutine> subs) {
		int currentLine = 0;
		for (int i = 0; i < subs.size(); i++) {
			currentLine += 2;
			for (int j = 0; j < subs.get(i).instructions.size(); j++) {
				subs.get(i).instructions.get(j).line = currentLine;
				currentLine++;
				currentLine += getCountOfLineEndsInString(PawnInstruction.getDisassembly(subs.get(i).instructions.get(j)));
			}
			currentLine += 2;
		}
	}

	public String getPtrText(GFLPawnScript scr) {
		StringBuilder sb = new StringBuilder("\n\n");
		for (PawnInstruction i : scr.instructions) {
			sb.append(getFormattedStringShort(i.pointer));
			int extraLines = getCountOfLineEndsInString(i.stringValue);
			for (int j = 0; j < extraLines; j++) {
				sb.append('\n');
			}
			if (i.getCommand() == 0x2F || i.getCommand() == 0x30) {
				sb.append("\n\n\n\n");
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	public static String getDisassemblyTextForArea(GFLPawnScript scr) {
		StringBuilder daText = new StringBuilder();
		List<PawnSubroutine> subs = PawnDisassembler.disassembleScript(scr);
		int currentLine = 0;
		for (int i = 0; i < subs.size(); i++) {
			subs.get(i).updateDisassembly();
			daText.append(subs.get(i).name);
			daText.append("\n{\n");
			currentLine += 2;
			List<String> dis = subs.get(i).getAllInstructionStrings(1);
			for (int j = 0; j < dis.size(); j++) {
				subs.get(i).instructions.get(j).line = currentLine;
				daText.append(dis.get(j));
				daText.append("\n");
				currentLine++;
				currentLine += getCountOfLineEndsInString(dis.get(j));
			}
			daText.append("}\n\n");
			currentLine += 2;
		}
		return daText.toString();
	}

	public String getDataText(List<PawnInstruction> dataPseudoInstructions) {
		StringBuilder sb = new StringBuilder();
		for (PawnInstruction i : dataPseudoInstructions) {
			sb.append(getFormattedString(i.cellValue));
			sb.append("\n");
		}
		return sb.toString();
	}

	public String getDataPtr(List<PawnInstruction> dataPseudoInstructions) {
		StringBuilder sb = new StringBuilder();
		int ptr = 0;
		for (PawnInstruction i : dataPseudoInstructions) {
			sb.append(getFormattedStringShort(ptr));
			sb.append("\n");
			ptr += 4;
		}
		return sb.toString();
	}

	/**
	 * The data words of {@code text}, one per line, each typed as four bytes in file order.
	 *
	 * @throws IllegalArgumentException naming the first line that is not a word, before anything is built: a
	 * blank or mistyped line threw out of the Commit button's listener where nobody saw it, and a short one
	 * silently became a different word (census H.36)
	 */
	public static List<PawnInstruction> getDataInstructions(String text) {
		String[] lines = text.split("\r?\n", -1);
		int last = lines.length;
		while (last > 0 && lines[last - 1].trim().isEmpty()) {
			last--;     //the text's own last line end, and nothing typed after it
		}
		List<PawnInstruction> ret = new ArrayList<>();
		int ptr = 0;
		for (int n = 0; n < last; n++) {
			int word;
			try {
				word = typedWord(lines[n]);
			} catch (IllegalArgumentException notAWord) {
				throw new IllegalArgumentException("line " + (n + 1) + ": " + ctrmap.util.Bytes.reason(notAWord), notAWord);
			}
			PawnInstruction i = new PawnInstruction(ptr, Integer.reverseBytes(word), "DATA ELEMENT");
			i.hasCompressedArgument = false;
			i.argumentCount = 0;
			ret.add(i);
			ptr += 4;
		}
		return ret;
	}

	/**
	 * A 32-bit word typed as four bytes in hex - "01 02 03 04", spaces anywhere - read in the order typed. The
	 * one reader of a typed word in this editor: anything but exactly eight hex digits is refused, saying what
	 * was typed (census H.36, H.37).
	 */
	public static int typedWord(String typed) {
		String digits = typed == null ? "" : typed.replaceAll("\\s", "");
		if (!digits.matches("[0-9A-Fa-f]{8}")) {
			throw new IllegalArgumentException("\"" + (typed == null ? "" : typed.trim())
					+ "\" is not a word - type four bytes in hex, like 01 02 03 04");
		}
		return (int) Long.parseLong(digits, 16);
	}

	public void updateDocument(boolean putCaretAtEnd, CaretMotion advanceCaret) throws BadLocationException {
		setPtrsByIndex(script.instructions);
		script.callInstructionListeners();
		script.setInstructionListeners();
		String newText = getDisassemblyTextForArea(script);
		String ptrText = getPtrText(script);
		final int originalLocation = disassemblyArea.getCaretPosition();
		int idx = originalLocation;
		if (putCaretAtEnd) {
			while (idx < newText.length() && newText.charAt(idx) != '\n') {
				idx++;
			}
		}
		final int finalIdx = idx;
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				disassemblyArea.setText(newText.trim());
				ptrs.setText(ptrText);
				setLines(PawnDisassembler.disassembleScript(script));
				int caretPlus = 0;
				if (advanceCaret == CaretMotion.FORWARD) {
					caretPlus = 1;
				} else if (advanceCaret == CaretMotion.BACKWARD) {
					caretPlus = -1;
				}
				disassemblyArea.setCaretPosition(Math.min(putCaretAtEnd ? finalIdx : originalLocation + caretPlus, newText.length() - 1));
				showPartyParam(); //the lines were renumbered after the caret moved
				int disLine = getCountOfLineEndsInString(newText, disassemblyArea.getCaretPosition());
				int lines = 0;
				for (int i = 0; i < ptrText.length(); i++) {
					if (ptrText.charAt(i) == '\n') {
						lines++;
					}
					if (lines == disLine) {
						ptrs.setCaretPosition(i);
						break;
					}
				}
			}
		});
	}

	public void setPrefixCategory(PawnPrefixEntry.Type cat) {
		savePrefixEntry();
		loaded = false;
		prefixEntryList.clear();
		currentEntry = null;
		currentIdx = -1;
		address.setText("");
		name.setText("");
		if (script != null) {
			List<PawnPrefixEntry> src = null;
			switch (cat) {
				case PUBLIC:
					src = script.publics;
					break;
				case NATIVE:
					src = script.natives;
					break;
				case LIBRARY:
					src = script.libraries;
					break;
				case PUBLIC_VAR:
					src = script.publicVars;
					break;
				case TAG:
					src = script.tags;
					break;
				case NAME:
					src = script.names;
					break;
			}
			currentEntries = src;
			if (src != null) {
				if (cat == PawnPrefixEntry.Type.PUBLIC) {
					for (int i = 0; i < src.size(); i++) {
						PawnPrefixEntry e = src.get(i);
						prefixEntryList.addElement(i + " - " + getFormattedStringLE(e.data[0]) + " " + getFormattedStringLE(e.data[1]));
					}
					prefixEntryList.addElement("main - " + getFormattedStringLE(script.mainEntryPointDummy.argumentCells[0]));
				} else {
					for (int i = 0; i < src.size(); i++) {
						PawnPrefixEntry e = src.get(i);
						prefixEntryList.addElement(i + " - " + getFormattedString(e.data[0]) + " " + getFormattedString(e.data[1]));
					}
				}
			}
		}
		loaded = true;
	}

	private PawnPrefixEntry.Type getTypeForIdx(int idx) {
		PawnPrefixEntry.Type type = null;
		switch (idx) {
			case 0:
				type = PawnPrefixEntry.Type.PUBLIC;
				break;
			case 1:
				type = PawnPrefixEntry.Type.NATIVE;
				break;
			case 2:
				type = PawnPrefixEntry.Type.LIBRARY;
				break;
			case 3:
				type = PawnPrefixEntry.Type.PUBLIC_VAR;
				break;
			case 4:
				type = PawnPrefixEntry.Type.TAG;
				break;
			case 5:
				type = PawnPrefixEntry.Type.NAME;
				break;
		}
		return type;
	}

	public void showPrefixEntry(int idx) {
		currentIdx = idx;
		if (currentEntries != null && idx >= 0) {
			if (prefixCat.getSelectedIndex() == 0) {
				//publics
				if (idx == currentEntries.size()) {
					currentEntry = new PawnPrefixEntry(8, PawnPrefixEntry.Type.PUBLIC, new int[]{script.mainEntryPointDummy.argumentCells[0], 0});
				} else {
					currentEntry = currentEntries.get(idx);
				}
				address.setText(getFormattedStringLE(currentEntry.data[0]));
				name.setText(getFormattedStringLE(currentEntry.data[1]));
			} else if (idx < currentEntries.size()) {
				PawnPrefixEntry e = currentEntries.get(idx);
				currentEntry = e;
				address.setText(getFormattedString(e.data[0]));
				name.setText(getFormattedString(e.data[1]));
			}
		}
	}

	/**
	 * Writes the two typed fields into the entry shown - both read before either is written, and a field that is
	 * not a word is said and writes nothing. It wrote the first before reading the second, from a list listener
	 * where the throw was invisible (census H.37). False when the entry was not saved.
	 */
	public boolean savePrefixEntry() {
		if (currentEntry != null && currentIdx != -1) {
			final int first;
			final int second;
			try {
				first = typedWord(address.getText());
				second = typedWord(name.getText());
			} catch (IllegalArgumentException notAWord) {
				ctrmap.Ui.error(this, "Prefix entry " + currentIdx + " was not saved: " + ctrmap.util.Bytes.reason(notAWord),
						"Script editor");
				return false;
			}
			if (currentEntry.type == PawnPrefixEntry.Type.PUBLIC) {
				currentEntry.data[0] = first;
				currentEntry.data[1] = second;
				if (currentIdx == currentEntries.size()) {
					script.mainEntryPointDummy.argumentCells[0] = currentEntry.data[0];
					script.mainEntryPointDummy.chkAddJumpListener();
					prefixEntryList.setElementAt("main - " + getFormattedStringLE(script.mainEntryPointDummy.argumentCells[0]), currentIdx);
				} else {
					prefixEntryList.setElementAt(currentIdx + " - " + getFormattedStringLE(currentEntry.data[0]) + " " + getFormattedStringLE(currentEntry.data[1]), currentIdx);
				}
			} else {
				currentEntry.data[0] = Integer.reverseBytes(first);
				currentEntry.data[1] = Integer.reverseBytes(second);
				prefixEntryList.setElementAt(currentIdx + " - " + getFormattedString(currentEntry.data[0]) + " " + getFormattedString(currentEntry.data[1]), currentIdx);
			}
		}
		return true;
	}

	public static int getCountOfLineEndsInString(String str) {
		return getCountOfLineEndsInString(str, str.length());
	}

	public static int getCountOfLineEndsInString(String str, int limit) {
		return getCountOfCharInString(str, '\n', limit);
	}

	public static int getCountOfCharInString(String str, char chara, int limit) {
		int cnt = 0;
		for (int i = 0; i < limit; i++) {
			if (str.charAt(i) == chara) {
				cnt++;
			}
		}
		return cnt;
	}

	public String getFormattedString(int instructionRaw) {
		String hexstring = Integer.toHexString(Integer.reverseBytes(instructionRaw));
		return ("00000000" + hexstring).substring(hexstring.length()).toUpperCase().replaceAll("..", "$0 ");
	}

	public String getFormattedStringLE(int instructionRaw) {
		String hexstring = Integer.toHexString(instructionRaw);
		return ("00000000" + hexstring).substring(hexstring.length()).toUpperCase().replaceAll("..", "$0 ");
	}

	public String getFormattedStringShort(int instructionRaw) {
		String hexstring = Integer.toHexString(instructionRaw);
		return ("0000" + hexstring).substring(hexstring.length()).toUpperCase().replaceAll("..", "$0 ");
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        assemblerOutputScrollPane = new javax.swing.JScrollPane();
        assemblerOutput = new javax.swing.JTextArea();
        disassemblyScrollPane = new javax.swing.JScrollPane();
        disassemblyArea = new javax.swing.JTextArea();
        btnTestAssembly = new javax.swing.JButton();
        btnSave = new javax.swing.JButton();
        prefixCat = new javax.swing.JComboBox<>();
        prefixScrollPane = new javax.swing.JScrollPane();
        prefixItemList = new javax.swing.JList<>();
        btnIsDataEdit = new javax.swing.JToggleButton();
        btnNewPrefixEntry = new javax.swing.JButton();
        btnRemovePrefixEntry = new javax.swing.JButton();
        btnSavePrefixEntry = new javax.swing.JButton();
        addressLabel = new javax.swing.JLabel();
        address = new javax.swing.JTextField();
        nameLabel = new javax.swing.JLabel();
        name = new javax.swing.JTextField();

        assemblerOutput.setEditable(false);
        assemblerOutput.setColumns(20);
        assemblerOutput.setFont(new java.awt.Font("SansSerif", 0, 12)); // NOI18N
        assemblerOutput.setRows(5);
        assemblerOutputScrollPane.setViewportView(assemblerOutput);

        disassemblyArea.setColumns(20);
        disassemblyArea.setFont(new java.awt.Font("Monospaced", 0, 14)); // NOI18N
        disassemblyArea.setRows(5);
        disassemblyArea.setMargin(new java.awt.Insets(0, 10, 2, 2));
        disassemblyScrollPane.setViewportView(disassemblyArea);

        btnTestAssembly.setText("Run assembler");
        btnTestAssembly.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnTestAssemblyActionPerformed(evt);
            }
        });

        btnSave.setText("Commit changes");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        prefixCat.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Publics", "Natives", "Libraries", "Public variables", "Tags", "Names" }));
        prefixCat.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                prefixCatActionPerformed(evt);
            }
        });

        prefixScrollPane.setViewportView(prefixItemList);

        btnIsDataEdit.setText("Edit data");
        btnIsDataEdit.setName(""); // NOI18N
        btnIsDataEdit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnIsDataEditActionPerformed(evt);
            }
        });

        btnNewPrefixEntry.setText("Add new");
        btnNewPrefixEntry.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNewPrefixEntryActionPerformed(evt);
            }
        });

        btnRemovePrefixEntry.setText("Remove");
        btnRemovePrefixEntry.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemovePrefixEntryActionPerformed(evt);
            }
        });

        btnSavePrefixEntry.setText("Save");
        btnSavePrefixEntry.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSavePrefixEntryActionPerformed(evt);
            }
        });

        addressLabel.setText("Address:");

        nameLabel.setText("Name:");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(assemblerOutputScrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, 600, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(prefixCat, javax.swing.GroupLayout.PREFERRED_SIZE, 258, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(prefixScrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, 330, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(addressLabel)
                            .addComponent(nameLabel)
                            .addComponent(btnSavePrefixEntry, javax.swing.GroupLayout.PREFERRED_SIZE, 75, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(btnRemovePrefixEntry, javax.swing.GroupLayout.PREFERRED_SIZE, 75, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(btnNewPrefixEntry)
                            .addComponent(address)
                            .addComponent(name, javax.swing.GroupLayout.DEFAULT_SIZE, 100, Short.MAX_VALUE))))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(disassemblyScrollPane, javax.swing.GroupLayout.DEFAULT_SIZE, 278, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(btnTestAssembly, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnSave, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnIsDataEdit, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(disassemblyScrollPane, javax.swing.GroupLayout.DEFAULT_SIZE, 409, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(btnTestAssembly)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnSave))
                            .addComponent(assemblerOutputScrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, 185, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                                .addGap(0, 0, Short.MAX_VALUE)
                                .addComponent(btnIsDataEdit))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(prefixCat, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(prefixScrollPane)
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(addressLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(address, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(nameLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(name, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                        .addComponent(btnNewPrefixEntry)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(btnRemovePrefixEntry)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(btnSavePrefixEntry)))))))
                .addContainerGap())
        );
    }// </editor-fold>//GEN-END:initComponents

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
		if (editData) {
			final List<PawnInstruction> data;
			try {
				data = getDataInstructions(disassemblyArea.getText());
			} catch (IllegalArgumentException notAWord) {
				ctrmap.Ui.error(this, "Not committed - " + ctrmap.util.Bytes.reason(notAWord), "Script editor");
				return;
			}
			script.data = data;
			script.updateRaw();
		} else {
			PawnAssembly asm = assemble();
			if (!asm.errors.isEmpty()) {
				//store(false) shows nothing on success, so a refusal has to be the loud one
				List<String> shown = asm.errors.subList(0, Math.min(asm.errors.size(), 8));
				ctrmap.Ui.error(this, "Not committed - " + asm.errors.size() + " line(s) did not assemble:\n"
						+ String.join("\n", shown) + (shown.size() < asm.errors.size() ? "\n... see the assembler output for the rest" : ""),
						"Script editor");
				return;
			}
		}
		zoneSaver.save(false);
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnTestAssemblyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnTestAssemblyActionPerformed
		if (!editData) {
			assemble();
		}
    }//GEN-LAST:event_btnTestAssemblyActionPerformed

	/**
	 * Assembles the text into the script and reloads it, or - when any line did
	 * not parse - refuses and leaves both untouched. loadScript rewrites the
	 * text from the instructions, so a line the assembler dropped would be gone
	 * for good: a mistyped PSUH_C used to vanish this way, with Commit writing
	 * the shortened script in the same click. The verdict goes to the output box.
	 */
	private PawnAssembly assemble() {
		PawnAssembly asm = PawnDisassembler.assembleScript(disassemblyArea.getText(), script);
		if (!asm.errors.isEmpty()) {
			assemblerOutput.setText("Assembler refused:\n" + asm.report() + asm.errors.size() + " line(s) did not assemble. The script was not changed.");
			return asm;
		}
		assemblerOutput.setText("Assembler running:\n" + asm.report() + "Assembled " + asm.getInstructionCount() + " instructions.");
		script.instructions.clear();
		for (PawnSubroutine sub : asm.subroutines) {
			script.instructions.addAll(sub.instructions);
		}
		script.updateRaw();
		loadScript(script);
		return asm;
	}

    private void prefixCatActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_prefixCatActionPerformed
		if (script != null) {
			PawnPrefixEntry.Type type = getTypeForIdx(prefixCat.getSelectedIndex());
			if (type != null) {
				setPrefixCategory(type);
			}
		}
    }//GEN-LAST:event_prefixCatActionPerformed

    private void btnIsDataEditActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnIsDataEditActionPerformed
		if (editData != btnIsDataEdit.isSelected()) {
			editData = btnIsDataEdit.isSelected();
			String tempSwapBuffer = textSwapBuffer;
			textSwapBuffer = disassemblyArea.getText();
			disassemblyArea.setText(tempSwapBuffer);
			if (btnIsDataEdit.isSelected()) {
				btnIsDataEdit.setText("Edit code");
				ptrs.setText(getDataPtr(script.data));
			} else {
				btnIsDataEdit.setText("Edit data");
				//THE CODE'S GUTTER BACK WITH THE CODE (census M.12): only the switch TO data set it, so the
				//data words' addresses stayed beside the code lines
				if (script != null) {
					ptrs.setText(getPtrText(script));
				}
			}
		}
    }//GEN-LAST:event_btnIsDataEditActionPerformed

    private void btnSavePrefixEntryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSavePrefixEntryActionPerformed
		//A REFUSED SAVE KEEPS WHAT WAS TYPED (2026-10-15): the answer was read by nobody, and the entry was shown again
		//as stored - over the text the refusal had just named, leaving nothing to correct
		if (savePrefixEntry()) {
			showPrefixEntry(prefixItemList.getSelectedIndex());
		}
    }//GEN-LAST:event_btnSavePrefixEntryActionPerformed

    private void btnNewPrefixEntryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNewPrefixEntryActionPerformed
		//NOTHING TO ADD TO BEFORE A SCRIPT IS OPEN (census M.78): the list is the open script's, and this threw on it
		if (currentEntries == null) {
			return;
		}
		PawnPrefixEntry.Type type = getTypeForIdx(prefixCat.getSelectedIndex());
		currentEntries.add(new PawnPrefixEntry(8, type, new int[2]));
		setPrefixCategory(type);
		prefixItemList.setSelectedIndex(currentEntries.size() - 1);
    }//GEN-LAST:event_btnNewPrefixEntryActionPerformed

    private void btnRemovePrefixEntryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemovePrefixEntryActionPerformed
		if (currentIdx != -1) {
			prefixItemList.setSelectedIndex(-1);
			currentEntries.remove(currentIdx);
			setPrefixCategory(getTypeForIdx(prefixCat.getSelectedIndex()));
		}
    }//GEN-LAST:event_btnRemovePrefixEntryActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JTextField address;
    private javax.swing.JLabel addressLabel;
    private javax.swing.JTextArea assemblerOutput;
    private javax.swing.JScrollPane assemblerOutputScrollPane;
    private javax.swing.JToggleButton btnIsDataEdit;
    private javax.swing.JButton btnNewPrefixEntry;
    private javax.swing.JButton btnRemovePrefixEntry;
    private javax.swing.JButton btnSave;
    private javax.swing.JButton btnSavePrefixEntry;
    private javax.swing.JButton btnTestAssembly;
    private javax.swing.JTextArea disassemblyArea;
    private javax.swing.JScrollPane disassemblyScrollPane;
    private javax.swing.JTextField name;
    private javax.swing.JLabel nameLabel;
    private javax.swing.JComboBox<String> prefixCat;
    private javax.swing.JList<String> prefixItemList;
    private javax.swing.JScrollPane prefixScrollPane;
    // End of variables declaration//GEN-END:variables
}
