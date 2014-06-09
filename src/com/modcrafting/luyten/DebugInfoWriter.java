package com.modcrafting.luyten;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import com.strobel.decompiler.languages.java.JavaTypeDecompilationResults.VariableTableEntry;

public class DebugInfoWriter {

	public enum WriteMode {
		KEEP_ORIGINAL_LEAVE_EMPTY_IF_NOT_EXISTS,
		KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS,
		OVERWRITE_ALL,
		DELETE;
	}

	private WriteMode sorceFileInfoWriteMode = WriteMode.KEEP_ORIGINAL_LEAVE_EMPTY_IF_NOT_EXISTS;
	private WriteMode lineNumberTableWriteMode = WriteMode.KEEP_ORIGINAL_LEAVE_EMPTY_IF_NOT_EXISTS;
	private WriteMode variableTableWriteMode = WriteMode.KEEP_ORIGINAL_LEAVE_EMPTY_IF_NOT_EXISTS;

	private Map<String, String> sourceFileNames = new HashMap<>();
	private Map<String, Map<String, TreeMap<Integer, Integer>>> lineNumbers = new HashMap<>();
	private Map<String, Map<String, List<VariableTableEntry>>> variableTables = new HashMap<>();

	public byte[] writeDebugInfo(byte[] inputClassBytes, String inputTypeSignature) {
		final ClassWriter writer = new ClassWriter(0);

		Map<String, TreeMap<Integer, Integer>> lineNumTableForType = lineNumbers.get(inputTypeSignature);
		if (lineNumTableForType == null)
			lineNumTableForType = new HashMap<>();
		Map<String, List<VariableTableEntry>> variableTableForType = variableTables.get(inputTypeSignature);
		if (variableTableForType == null)
			variableTableForType = new HashMap<>();

		final String sourceFileName = sourceFileNames.get(inputTypeSignature);
		final Map<String, TreeMap<Integer, Integer>> lineNumsOfType = lineNumTableForType;
		final Map<String, List<VariableTableEntry>> variablesOfType = variableTableForType;

		ClassVisitor visitor = new ClassVisitor(Opcodes.ASM4, writer) {
			boolean isSourceInfoExists = false;

			@Override
			public void visitSource(String source, String debug) {
				if (source != null && source.trim().length() > 0)
					isSourceInfoExists = true;
				if ((isSourceInfoExists && sorceFileInfoWriteMode == WriteMode.KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS)
						|| sorceFileInfoWriteMode == WriteMode.KEEP_ORIGINAL_LEAVE_EMPTY_IF_NOT_EXISTS) {
					super.visitSource(sourceFileName, debug);
				}
			}

			@Override
			public void visitEnd() {
				if ((!isSourceInfoExists && sorceFileInfoWriteMode == WriteMode.KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS)
						|| sorceFileInfoWriteMode == WriteMode.OVERWRITE_ALL) {
					if (sourceFileName != null) {
						super.visitSource(sourceFileName, null);
					}
				}
				super.visitEnd();
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String desc,
					String signature, String[] exceptions) {

				final TreeMap<Integer, Integer> lineNumsOfMethod = lineNumsOfType.get(name + "|" + desc);
				final List<VariableTableEntry> variablesOfMethod = variablesOfType.get(name + "|" + desc);

				MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions);
				if (methodVisitor != null) {
					methodVisitor = new MethodVisitor(Opcodes.ASM4, methodVisitor) {
						boolean isLineNumberTableExists = false;
						boolean isVariableTableExists = false;
						Set<Integer> validOffsets = new HashSet<>();

						@Override
						public void visitValidOffset(int offset) {
							validOffsets.add(offset);
							super.visitValidOffset(offset);
						}

						@Override
						public void visitLineNumber(int line, Label start) {
							isLineNumberTableExists = true;
							if ((isLineNumberTableExists && lineNumberTableWriteMode == WriteMode.KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS)
									|| lineNumberTableWriteMode == WriteMode.KEEP_ORIGINAL_LEAVE_EMPTY_IF_NOT_EXISTS) {
								super.visitLineNumber(line, start);
							}
						}

						@Override
						public void visitLocalVariable(String name, String desc,
								String signature, Label start, Label end, int index) {
							isVariableTableExists = true;
							if ((isVariableTableExists && variableTableWriteMode == WriteMode.KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS)
									|| variableTableWriteMode == WriteMode.KEEP_ORIGINAL_LEAVE_EMPTY_IF_NOT_EXISTS) {
								super.visitLocalVariable(sourceFileName, desc,
										signature, start, end, index);
							}
						}

						@Override
						public void visitEnd() {
							if ((!isLineNumberTableExists && lineNumberTableWriteMode == WriteMode.KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS)
									|| lineNumberTableWriteMode == WriteMode.OVERWRITE_ALL) {
								addLineNumberTable();
							}
							if ((!isVariableTableExists && variableTableWriteMode == WriteMode.KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS)
									|| variableTableWriteMode == WriteMode.OVERWRITE_ALL) {
								addVariableTable();
							}
							super.visitEnd();
						}

						private void addLineNumberTable() {
							if (lineNumsOfMethod == null || lineNumsOfMethod.size() < 1)
								return;
							if (validOffsets.size() < 1)
								return;
							if (!validOffsets.contains(0))
								validOffsets.add(0);

							for (Integer offset : lineNumsOfMethod.keySet()) {
								if (validOffsets.contains(offset)) {
									Integer lineNum = lineNumsOfMethod.get(offset);
									super.visitLineNumber(lineNum, new Label(offset));
								}
							}
						}

						private void addVariableTable() {
							if (variablesOfMethod == null || variablesOfMethod.size() < 1)
								return;
							if (validOffsets.size() < 1)
								return;
							if (!validOffsets.contains(0))
								validOffsets.add(0);

							for (VariableTableEntry entry : variablesOfMethod) {
								if (validOffsets.contains(entry.fromOffset)
										&& validOffsets.contains(entry.toOffset)) {
									super.visitLocalVariable(entry.name, entry.signature,
											null, new Label(entry.fromOffset),
											new Label(entry.toOffset), entry.slot);
								}
							}
						}
					};
				}
				return methodVisitor;
			}
		};

		ClassReader reader = new ClassReader(inputClassBytes);
		reader.accept(visitor, 0);
		byte[] outputClassBytes = writer.toByteArray();
		return outputClassBytes;
	}

	public void setSorceFileInfoWriteMode(WriteMode sorceFileInfoWriteMode) {
		this.sorceFileInfoWriteMode = sorceFileInfoWriteMode;
	}

	public void setLineNumberTableWriteMode(WriteMode lineNumberTableWriteMode) {
		this.lineNumberTableWriteMode = lineNumberTableWriteMode;
	}

	public void setVariableTableWriteMode(WriteMode variableTableWriteMode) {
		this.variableTableWriteMode = variableTableWriteMode;
	}

	public void addSourceFileName(String typeSignature, String sourceFileName) {
		this.sourceFileNames.put(typeSignature, sourceFileName);
	}

	public void addSourceFileNames(Map<String, String> sourceFileNames) {
		this.sourceFileNames.putAll(sourceFileNames);
	}

	public void addLineNumbers(Map<String, Map<String, TreeMap<Integer, Integer>>> lineNumbers) {
		this.lineNumbers.putAll(lineNumbers);
	}

	public void addVariableTables(Map<String, Map<String, List<VariableTableEntry>>> variableTables) {
		this.variableTables.putAll(variableTables);
	}
}
