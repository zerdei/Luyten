package com.strobel.decompiler.languages.java;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.strobel.assembler.ir.Instruction;
import com.strobel.assembler.ir.OpCode;
import com.strobel.assembler.metadata.MethodBody;
import com.strobel.assembler.metadata.MethodDefinition;
import com.strobel.assembler.metadata.ParameterDefinition;
import com.strobel.assembler.metadata.TypeReference;
import com.strobel.assembler.metadata.VariableDefinition;
import com.strobel.assembler.metadata.VariableDefinitionCollection;
import com.strobel.assembler.metadata.VariableReference;
import com.strobel.decompiler.ast.Variable;
import com.strobel.decompiler.languages.LineNumberPosition;
import com.strobel.decompiler.languages.TypeDecompilationResults;
import com.strobel.decompiler.languages.java.ast.AstNode;
import com.strobel.decompiler.languages.java.ast.AstType;
import com.strobel.decompiler.languages.java.ast.BlockStatement;
import com.strobel.decompiler.languages.java.ast.CatchClause;
import com.strobel.decompiler.languages.java.ast.CompilationUnit;
import com.strobel.decompiler.languages.java.ast.ConstructorDeclaration;
import com.strobel.decompiler.languages.java.ast.DepthFirstAstVisitor;
import com.strobel.decompiler.languages.java.ast.Expression;
import com.strobel.decompiler.languages.java.ast.ForEachStatement;
import com.strobel.decompiler.languages.java.ast.ForStatement;
import com.strobel.decompiler.languages.java.ast.Keys;
import com.strobel.decompiler.languages.java.ast.MethodDeclaration;
import com.strobel.decompiler.languages.java.ast.ParameterDeclaration;
import com.strobel.decompiler.languages.java.ast.Statement;
import com.strobel.decompiler.languages.java.ast.TryCatchStatement;
import com.strobel.decompiler.languages.java.ast.VariableDeclarationStatement;

public class JavaTypeDecompilationResults extends TypeDecompilationResults {

	private final CompilationUnit compilationUnit;
	private Map<String, Map<String, List<VariableTableEntry>>> variableTablesRaw;

	public static class VariableTableEntry {
		public String name;
		public int slot;
		public int declareOffset;
		public int fromOffset;
		public int toOffset;
		public String signature;

		public VariableTableEntry(String name, int slot, int declareOffset,
				int fromOffset, int toOffset, String signature) {
			this.name = name;
			this.slot = slot;
			this.declareOffset = declareOffset;
			this.fromOffset = fromOffset;
			this.toOffset = toOffset;
			this.signature = signature;
		}

		@Override
		public String toString() {
			return name + "; " + slot + "; " + "[" + declareOffset + "] " + fromOffset +
					" -> " + toOffset + " (" + (toOffset - fromOffset) + "); " + signature;
		}
	}

	// Common fields for processing current method's variable table
	private AstNode methodNode;
	private MethodBody methodBody;
	private int codeSize;
	private String declaringTypeSignature;
	private String methodName;
	private String methodSignature;
	private String methodKey;
	private VariableDefinitionCollection methodVariables;
	private Collection<ParameterDeclaration> methodNodeParameters;
	private List<VariableTableEntry> currentVariableTable;

	// Common fields for processing current variable
	private int firstOffsetInBlock;
	private int lastOffsetInBlock;

	public JavaTypeDecompilationResults(List<LineNumberPosition> lineNumberPositions, CompilationUnit compilationUnit) {
		super(lineNumberPositions);
		this.compilationUnit = compilationUnit;
	}

	public CompilationUnit getCompilationUnit() {
		return compilationUnit;
	}

	public synchronized Map<String, Map<String, List<VariableTableEntry>>> generateVariableTables() {
		if (compilationUnit == null)
			return new HashMap<>();
		variableTablesRaw = new HashMap<>();

		compilationUnit.acceptVisitor(new DepthFirstAstVisitor<Void, Void>() {
			@Override
			public Void visitMethodDeclaration(MethodDeclaration node, Void data) {
				try {
					MethodDefinition method = node.getUserData(Keys.METHOD_DEFINITION);
					addMethodVariables(method, node);
				} catch (Exception e) {
					e.printStackTrace();
				}
				return super.visitMethodDeclaration(node, data);
			}

			@Override
			public Void visitConstructorDeclaration(ConstructorDeclaration node, Void data) {
				try {
					MethodDefinition method = node.getUserData(Keys.METHOD_DEFINITION);
					addMethodVariables(method, node);
				} catch (Exception e) {
					e.printStackTrace();
				}
				return super.visitConstructorDeclaration(node, data);
			}
		}, null);

		Map<String, Map<String, List<VariableTableEntry>>> variableTables = cleanUpRawVariableTables();
		return variableTables;
	}

	private void addMethodVariables(MethodDefinition method, AstNode methodNode) {
		if (method == null || method.getDeclaringType() == null)
			return;
		this.methodNode = methodNode;
		if (!(methodNode instanceof MethodDeclaration) && !(methodNode instanceof ConstructorDeclaration))
			return;
		methodBody = method.getBody();
		if (methodBody == null)
			return;
		codeSize = methodBody.getCodeSize();
		if (codeSize < 1)
			return;
		methodVariables = methodBody.getVariables();

		declaringTypeSignature = method.getDeclaringType().getErasedSignature();
		if (declaringTypeSignature == null || declaringTypeSignature.trim().length() < 1)
			return;
		methodName = method.getName();
		if (methodName == null || methodName.trim().length() < 1)
			return;
		methodSignature = method.getErasedSignature();
		if (methodSignature == null || methodSignature.trim().length() < 1)
			return;
		methodKey = methodName + "|" + methodSignature;

		methodNodeParameters = Collections.emptyList();
		if (methodNode instanceof MethodDeclaration) {
			methodNodeParameters = ((MethodDeclaration) methodNode).getParameters();
		} else if (methodNode instanceof ConstructorDeclaration) {
			methodNodeParameters = ((ConstructorDeclaration) methodNode).getParameters();
		}

		currentVariableTable = new ArrayList<>();
		addThisVariable();
		addParameters();
		addDeclaredVariables();
		addCatchClauseVariables();
		addForeachVariables();

		if (currentVariableTable.size() > 0) {
			Map<String, List<VariableTableEntry>> varTablesOfType = variableTablesRaw.get(declaringTypeSignature);
			if (varTablesOfType == null) {
				varTablesOfType = new HashMap<>();
				variableTablesRaw.put(declaringTypeSignature, varTablesOfType);
			}
			varTablesOfType.put(methodKey, currentVariableTable);
		}
	}

	private void addThisVariable() {
		if (methodVariables == null)
			return;
		for (VariableDefinition varDef : methodVariables) {
			if (varDef != null && "this".equals(varDef.getName())) {
				int slot = varDef.getSlot();
				TypeReference variableType = varDef.getVariableType();
				if (slot < 0 || variableType == null)
					return;
				String typeSignature = variableType.getErasedSignature();
				if (typeSignature == null || typeSignature.trim().length() < 1)
					return;

				currentVariableTable.add(new VariableTableEntry("this", slot, 0, 0, codeSize, typeSignature));
				return;
			}
		}
	}

	private void addParameters() {
		if (methodNodeParameters == null)
			return;
		for (ParameterDeclaration param : methodNodeParameters) {
			if (param == null)
				continue;
			ParameterDefinition paramDef = param.getUserData(Keys.PARAMETER_DEFINITION);
			if (paramDef == null)
				continue;
			String paramName = param.getName();
			if (paramName == null || paramName.trim().length() < 1)
				continue;

			int slot = paramDef.getSlot();
			TypeReference paramType = paramDef.getParameterType();
			if (slot < 0 || paramType == null)
				continue;
			String typeSignature = paramType.getErasedSignature();
			if (typeSignature == null || typeSignature.trim().length() < 1)
				continue;

			currentVariableTable.add(new VariableTableEntry(paramName, slot, 0, 0, codeSize, typeSignature));
		}
	}

	private void addDeclaredVariables() {
		if (methodNode == null)
			return;
		methodNode.acceptVisitor(new DepthFirstAstVisitor<Void, Void>() {
			@Override
			public Void visitVariableDeclaration(VariableDeclarationStatement node, Void data) {
				try {
					if (node != null && isNodeBelongsToCurrentMethodsDeclaringType(node)) {
						Variable var = node.getUserData(Keys.VARIABLE);
						if (var != null) {
							VariableDefinition varDef = var.getOriginalVariable();
							if (varDef != null) {
								addDeclaredVariable(node, var, varDef);
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
				return super.visitVariableDeclaration(node, data);
			}
		}, null);
	}

	private boolean isNodeBelongsToCurrentMethodsDeclaringType(AstNode node) {
		if (node == null || node.getParent() == null)
			return false;
		AstNode rootNode = node.getParent();
		while (rootNode != null &&
				!(rootNode instanceof MethodDeclaration) &&
				!(rootNode instanceof ConstructorDeclaration)) {
			rootNode = rootNode.getParent();
		}

		MethodDefinition rootMethod = null;
		if (rootNode instanceof MethodDeclaration) {
			rootMethod = ((MethodDeclaration) rootNode).getUserData(Keys.METHOD_DEFINITION);
		} else if (rootNode instanceof ConstructorDeclaration) {
			rootMethod = ((ConstructorDeclaration) rootNode).getUserData(Keys.METHOD_DEFINITION);
		}
		if (rootMethod == null || rootMethod.getDeclaringType() == null)
			return false;

		String rootMethodDeclaringTypeSignature = rootMethod.getDeclaringType().getErasedSignature();
		return declaringTypeSignature.equals(rootMethodDeclaringTypeSignature);
	}

	private void addDeclaredVariable(VariableDeclarationStatement node, Variable var, VariableDefinition varDef) {
		String varName = var.getName();
		if (varName == null || varName.trim().length() < 1)
			return;
		int slot = varDef.getSlot();
		TypeReference variableType = varDef.getVariableType();
		if (slot < 0 || variableType == null)
			return;
		String typeSignature = variableType.getErasedSignature();
		if (typeSignature == null || typeSignature.trim().length() < 1)
			return;

		AstType nodeType = node.getType();
		if (nodeType != null) {
			TypeReference nodeTypeRef = nodeType.toTypeReference();
			if (nodeTypeRef != null) {
				
				// fix boolean (variableType sometimes resolves to int)				
				String typeFullName = nodeTypeRef.getFullName();
				if ("boolean".equals(typeFullName)) {
					String nodeTypeSignature = nodeTypeRef.getErasedSignature();
					if (nodeTypeSignature != null && nodeTypeSignature.trim().length() > 0) {
						typeSignature = nodeTypeSignature;
					}
				}
				
				// fix Null type if possible
				if (typeSignature.equals("L__Null;") || typeSignature.equals("L__Bottom;")) {
					String resolvedSiganture = nodeTypeRef.getErasedSignature();
					if (resolvedSiganture != null && resolvedSiganture.trim().length() > 0) {
						typeSignature = resolvedSiganture;
					}
				}
				
			}
		}

		AstNode statementNode = node;
		AstNode rootNode = node.getParent();
		while (rootNode != null &&
				!(rootNode instanceof BlockStatement) &&
				!(rootNode instanceof ForStatement) &&
				!(rootNode instanceof TryCatchStatement) &&
				!(rootNode instanceof MethodDeclaration) &&
				!(rootNode instanceof ConstructorDeclaration)) {
			statementNode = rootNode;
			rootNode = rootNode.getParent();
		}
		if (rootNode == null || rootNode instanceof MethodDeclaration || rootNode instanceof ConstructorDeclaration)
			return;

		firstOffsetInBlock = Expression.MYSTERY_OFFSET;
		lastOffsetInBlock = Expression.MYSTERY_OFFSET;
		int declareOffset = Expression.MYSTERY_OFFSET;
		int variableFirstAssignedOffset = Expression.MYSTERY_OFFSET;

		if (rootNode instanceof BlockStatement && statementNode instanceof Statement) {
			// Normal block, declared variable
			if (rootNode.getParent() instanceof MethodDeclaration) {
				lastOffsetInBlock = codeSize;
			} else {
				findFirstAndLastKnownOffsetInBlock(rootNode);
				lastOffsetInBlock = getNextValidOffset(lastOffsetInBlock);
			}
			declareOffset = findFirstKnownOffsetIncludeNextSiblings(statementNode);
			variableFirstAssignedOffset = getOffsetAfterFirstStoreInstruction(declareOffset, slot);

		} else if (rootNode instanceof ForStatement) {
			// Normal for cycle, initializer variable
			findFirstAndLastKnownOffsetInBlock(rootNode);
			lastOffsetInBlock = getNextValidOffset(lastOffsetInBlock);
			declareOffset = firstOffsetInBlock;
			variableFirstAssignedOffset = getOffsetAfterFirstStoreInstruction(declareOffset, slot);

		} else if (rootNode instanceof TryCatchStatement) {
			// Try with resources, resource variable
			findFirstAndLastKnownOffsetInBlock(rootNode);
			declareOffset = firstOffsetInBlock;
			variableFirstAssignedOffset = getOffsetAfterFirstStoreInstruction(declareOffset, slot);

			firstOffsetInBlock = Expression.MYSTERY_OFFSET;
			lastOffsetInBlock = Expression.MYSTERY_OFFSET;
			findFirstAndLastKnownOffsetInBlock(((TryCatchStatement) rootNode).getTryBlock());
			lastOffsetInBlock = getNextValidOffset(lastOffsetInBlock);
		}

		if (declareOffset == Expression.MYSTERY_OFFSET ||
				variableFirstAssignedOffset == Expression.MYSTERY_OFFSET ||
				lastOffsetInBlock == Expression.MYSTERY_OFFSET ||
				declareOffset < 0 || declareOffset > codeSize ||
				variableFirstAssignedOffset < 0 || variableFirstAssignedOffset > codeSize ||
				lastOffsetInBlock < 0 || lastOffsetInBlock > codeSize ||
				declareOffset > variableFirstAssignedOffset ||
				lastOffsetInBlock <= variableFirstAssignedOffset) {
			return;
		}

		currentVariableTable.add(new VariableTableEntry(varName, slot, declareOffset,
				variableFirstAssignedOffset, lastOffsetInBlock, typeSignature));
	}

	private int findFirstKnownOffsetIncludeNextSiblings(AstNode node) {
		if (node == null)
			return Expression.MYSTERY_OFFSET;
		int offset = Expression.MYSTERY_OFFSET;
		if (node instanceof Expression) {
			offset = ((Expression) node).getOffset();
		} else if (node instanceof Statement) {
			offset = ((Statement) node).getOffset();
		}
		if (offset != Expression.MYSTERY_OFFSET)
			return offset;

		offset = findFirstKnownOffsetIncludeNextSiblings(node.getFirstChild());
		if (offset != Expression.MYSTERY_OFFSET)
			return offset;

		offset = findFirstKnownOffsetIncludeNextSiblings(node.getNextSibling());
		if (offset != Expression.MYSTERY_OFFSET)
			return offset;

		return Expression.MYSTERY_OFFSET;
	}

	private void findFirstAndLastKnownOffsetInBlock(AstNode rootNode) {
		if (rootNode == null)
			return;
		int offset = Expression.MYSTERY_OFFSET;
		if (rootNode instanceof Expression) {
			offset = ((Expression) rootNode).getOffset();
		} else if (rootNode instanceof Statement) {
			offset = ((Statement) rootNode).getOffset();
		}
		if (offset != Expression.MYSTERY_OFFSET) {
			if (firstOffsetInBlock == Expression.MYSTERY_OFFSET || offset < firstOffsetInBlock)
				firstOffsetInBlock = offset;
			if (lastOffsetInBlock == Expression.MYSTERY_OFFSET || offset > lastOffsetInBlock)
				lastOffsetInBlock = offset;
		}
		for (AstNode child : rootNode.getChildren()) {
			findFirstAndLastKnownOffsetInBlock(child);
		}
	}

	private int getNextValidOffset(int offset) {
		if (offset == Expression.MYSTERY_OFFSET)
			return Expression.MYSTERY_OFFSET;
		if (methodBody.getInstructions() == null)
			return Expression.MYSTERY_OFFSET;
		Instruction current = methodBody.getInstructions().tryGetAtOffset(offset);
		if (current == null)
			return Expression.MYSTERY_OFFSET;
		return offset + current.getSize();
	}

	private int getOffsetAfterFirstStoreInstruction(int searchFromOffset, int slot) {
		if (searchFromOffset == Expression.MYSTERY_OFFSET || slot < 0)
			return Expression.MYSTERY_OFFSET;
		if (methodBody.getInstructions() == null)
			return Expression.MYSTERY_OFFSET;
		Instruction current = methodBody.getInstructions().tryGetAtOffset(searchFromOffset);
		if (current == null)
			return Expression.MYSTERY_OFFSET;

		while (current != null) {
			if (current.getOpCode() == null || current.getOffset() < 0 || current.getSize() < 1)
				return Expression.MYSTERY_OFFSET;
			int storeInstructionSlot = getSlotIfStoreInstruction(current);
			if (storeInstructionSlot == slot) {
				return current.getOffset() + current.getSize();
			}
			current = current.getNext();
		}
		return Expression.MYSTERY_OFFSET;
	}

	private int getSlotIfStoreInstruction(Instruction current) {
		if (current == null || current.getOpCode() == null)
			return -1;
		OpCode opCode = current.getOpCode();
		switch (opCode) {
			case ISTORE_0:
			case LSTORE_0:
			case FSTORE_0:
			case DSTORE_0:
			case ASTORE_0:
				return 0;

			case ISTORE_1:
			case LSTORE_1:
			case FSTORE_1:
			case DSTORE_1:
			case ASTORE_1:
				return 1;

			case ISTORE_2:
			case LSTORE_2:
			case FSTORE_2:
			case DSTORE_2:
			case ASTORE_2:
				return 2;

			case ISTORE_3:
			case LSTORE_3:
			case FSTORE_3:
			case DSTORE_3:
			case ASTORE_3:
				return 3;

			case ISTORE:
			case LSTORE:
			case FSTORE:
			case DSTORE:
			case ASTORE:
				Object operand = current.<Object> getOperand(0);
				if (operand instanceof VariableDefinition) {
					return ((VariableDefinition) operand).getSlot();
				} else if (operand instanceof VariableReference) {
					return ((VariableReference) operand).getSlot();
				} else {
					return -1;
				}

			default:
				return -1;
		}
	}

	private void addCatchClauseVariables() {
		if (methodNode == null)
			return;
		methodNode.acceptVisitor(new DepthFirstAstVisitor<Void, Void>() {
			@Override
			public Void visitCatchClause(CatchClause node, Void data) {
				try {
					if (node != null && isNodeBelongsToCurrentMethodsDeclaringType(node)) {
						Variable var = node.getUserData(Keys.VARIABLE);
						if (var != null) {
							VariableDefinition varDef = var.getOriginalVariable();
							if (varDef != null) {
								addCatchClauseOrForeachVariable(node, var, varDef);
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
				return super.visitCatchClause(node, data);
			}
		}, null);
	}

	private void addForeachVariables() {
		if (methodNode == null)
			return;
		methodNode.acceptVisitor(new DepthFirstAstVisitor<Void, Void>() {
			@Override
			public Void visitForEachStatement(ForEachStatement node, Void data) {
				try {
					if (node != null && isNodeBelongsToCurrentMethodsDeclaringType(node)) {
						Variable var = node.getUserData(Keys.VARIABLE);
						if (var != null) {
							VariableDefinition varDef = var.getOriginalVariable();
							if (varDef != null) {
								addCatchClauseOrForeachVariable(node, var, varDef);
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
				return super.visitForEachStatement(node, data);
			}
		}, null);
	}

	private void addCatchClauseOrForeachVariable(AstNode node, Variable var, VariableDefinition varDef) {
		String varName = var.getName();
		if (varName == null || varName.trim().length() < 1)
			return;
		int slot = varDef.getSlot();
		TypeReference variableType = varDef.getVariableType();
		if (slot < 0 || variableType == null)
			return;
		String typeSignature = variableType.getErasedSignature();
		if (typeSignature == null || typeSignature.trim().length() < 1)
			return;

		firstOffsetInBlock = Expression.MYSTERY_OFFSET;
		lastOffsetInBlock = Expression.MYSTERY_OFFSET;
		int declareOffset = Expression.MYSTERY_OFFSET;
		int variableFirstAssignedOffset = Expression.MYSTERY_OFFSET;

		if (node instanceof CatchClause) {
			findFirstAndLastKnownOffsetInBlock(node);
			lastOffsetInBlock = getNextValidOffset(lastOffsetInBlock);
			declareOffset = firstOffsetInBlock;
			variableFirstAssignedOffset = declareOffset;

		} else if (node instanceof ForEachStatement) {
			findFirstAndLastKnownOffsetInBlock(node);
			lastOffsetInBlock = getNextValidOffset(lastOffsetInBlock);
			declareOffset = firstOffsetInBlock;
			variableFirstAssignedOffset = getOffsetAfterFirstStoreInstruction(declareOffset, slot);
		}

		if (declareOffset == Expression.MYSTERY_OFFSET ||
				variableFirstAssignedOffset == Expression.MYSTERY_OFFSET ||
				lastOffsetInBlock == Expression.MYSTERY_OFFSET ||
				declareOffset < 0 || declareOffset > codeSize ||
				variableFirstAssignedOffset < 0 || variableFirstAssignedOffset > codeSize ||
				lastOffsetInBlock < 0 || lastOffsetInBlock > codeSize ||
				declareOffset > variableFirstAssignedOffset ||
				lastOffsetInBlock <= variableFirstAssignedOffset) {
			return;
		}

		currentVariableTable.add(new VariableTableEntry(varName, slot, declareOffset,
				variableFirstAssignedOffset, lastOffsetInBlock, typeSignature));
	}

	private Map<String, Map<String, List<VariableTableEntry>>> cleanUpRawVariableTables() {
		Map<String, Map<String, List<VariableTableEntry>>> generatedVariableTables = new HashMap<>();
		for (String typeStr : variableTablesRaw.keySet()) {
			Map<String, List<VariableTableEntry>> variableTablesOfType = variableTablesRaw.get(typeStr);

			if (variableTablesOfType != null) {
				Map<String, List<VariableTableEntry>> variableTablesOfTypeCleaned = new HashMap<>();
				for (String methodKey : variableTablesOfType.keySet()) {
					List<VariableTableEntry> variableTable = variableTablesOfType.get(methodKey);

					if (variableTable != null) {
						List<VariableTableEntry> variableTableCleaned = cleanUpVariableTable(variableTable);
						if (variableTableCleaned.size() > 0) {
							variableTablesOfTypeCleaned.put(methodKey, variableTableCleaned);
						}
					}

				}
				if (variableTablesOfTypeCleaned.size() > 0) {
					generatedVariableTables.put(typeStr, variableTablesOfTypeCleaned);
				}
			}

		}
		return generatedVariableTables;
	}

	private List<VariableTableEntry> cleanUpVariableTable(List<VariableTableEntry> variableTable) {
		if (variableTable == null || variableTable.size() < 2)
			return variableTable;
		int tableSize = variableTable.size();
		List<VariableTableEntry> variableTableCleaned = new ArrayList<>();
		List<VariableTableEntry> inputGroup = new ArrayList<>();
		inputGroup.addAll(variableTable);

		for (int i = 0; i < tableSize; i++) {
			List<VariableTableEntry> hideGroup = new ArrayList<>();
			List<VariableTableEntry> nextLoopGroup = new ArrayList<>();
			VariableTableEntry first = null;

			for (VariableTableEntry entry : inputGroup) {
				if (entry == null || entry.signature == null)
					continue;
				if (entry.signature.equals("L__Null;") || entry.signature.equals("L__Bottom;"))
					continue;
				if (first == null) {
					first = entry;
				} else {
					if (first.slot == entry.slot ||
							(first.name != null && first.name.equals(entry.name))) {
						hideGroup.add(entry);
					} else {
						nextLoopGroup.add(entry);
					}
				}
			}

			if (first != null) {
				if (hideGroup.size() < 1) {
					variableTableCleaned.add(first);
				} else {
					hideGroup.add(first);
					variableTableCleaned.addAll(cleanUpHideGroup(hideGroup));
				}
			} else {
				break;
			}
			inputGroup = nextLoopGroup;
		}

		Collections.sort(variableTableCleaned, new Comparator<VariableTableEntry>() {
			@Override
			public int compare(VariableTableEntry o1, VariableTableEntry o2) {
				return new Integer(o1.fromOffset).compareTo(o2.fromOffset);
			}
		});
		return variableTableCleaned;
	}

	private List<VariableTableEntry> cleanUpHideGroup(List<VariableTableEntry> hideGroup) {
		List<VariableTableEntry> cleanedHideGroup = new ArrayList<>();
		Collections.sort(hideGroup, new Comparator<VariableTableEntry>() {
			@Override
			public int compare(VariableTableEntry o1, VariableTableEntry o2) {
				return new Integer(o1.declareOffset).compareTo(o2.declareOffset);
			}
		});

		int hideGroupSize = hideGroup.size();
		for (int i = 0; i < hideGroupSize; i++) {
			if (i + 1 >= hideGroupSize) {
				cleanedHideGroup.add(hideGroup.get(i));
			} else {
				VariableTableEntry currentEntry = hideGroup.get(i);
				VariableTableEntry nextEntry = hideGroup.get(i + 1);
				boolean isNextEntryHides = currentEntry.toOffset > nextEntry.declareOffset;
				if (!isNextEntryHides) {
					cleanedHideGroup.add(currentEntry);
				} else {
					currentEntry.toOffset = nextEntry.declareOffset;
					if (currentEntry.toOffset > currentEntry.fromOffset) {
						cleanedHideGroup.add(currentEntry);
					}
				}
			}
		}
		return cleanedHideGroup;
	}
}
