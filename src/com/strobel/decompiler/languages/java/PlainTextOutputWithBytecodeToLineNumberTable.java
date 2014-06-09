package com.strobel.decompiler.languages.java;

import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import com.strobel.assembler.metadata.MethodDefinition;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.decompiler.PlainTextOutput;
import com.strobel.decompiler.languages.java.ast.AstNode;
import com.strobel.decompiler.languages.java.ast.ConstructorDeclaration;
import com.strobel.decompiler.languages.java.ast.Keys;
import com.strobel.decompiler.languages.java.ast.MethodDeclaration;

public class PlainTextOutputWithBytecodeToLineNumberTable extends PlainTextOutput {

	private Map<String, Map<String, TreeMap<Integer, Integer>>> generatedLineNumbersRaw = new HashMap<>();

	public PlainTextOutputWithBytecodeToLineNumberTable() {
		super();
	}

	public PlainTextOutputWithBytecodeToLineNumberTable(Writer writer) {
		super(writer);
	}

	protected void registerGeneratedLineNumber(AstNode expressionNode, int offset, int lineNumber) {
		AstNode methodNode = expressionNode;
		while (methodNode != null &&
				!(methodNode instanceof MethodDeclaration) &&
				!(methodNode instanceof ConstructorDeclaration)) {
			methodNode = methodNode.getParent();
		}

		if (!(methodNode instanceof MethodDeclaration || methodNode instanceof ConstructorDeclaration))
			return;
		MethodDefinition method = methodNode.getUserData(Keys.METHOD_DEFINITION);
		if (method == null)
			return;
		TypeDefinition declaringType = method.getDeclaringType();
		if (declaringType == null)
			return;

		String declaringTypeSignature = declaringType.getErasedSignature();
		if (declaringTypeSignature == null || declaringTypeSignature.trim().length() < 1)
			return;
		String methodName = method.getName();
		if (methodName == null || methodName.trim().length() < 1)
			return;
		String methodSignature = method.getErasedSignature();
		if (methodSignature == null || methodSignature.trim().length() < 1)
			return;
		String methodKey = methodName + "|" + methodSignature;

		Map<String, TreeMap<Integer, Integer>> lineNumsOfType = generatedLineNumbersRaw.get(declaringTypeSignature);
		if (lineNumsOfType == null) {
			lineNumsOfType = new HashMap<>();
			generatedLineNumbersRaw.put(declaringTypeSignature, lineNumsOfType);
		}
		TreeMap<Integer, Integer> lineNumsOfMethod = lineNumsOfType.get(methodKey);
		if (lineNumsOfMethod == null) {
			lineNumsOfMethod = new TreeMap<>();
			lineNumsOfType.put(methodKey, lineNumsOfMethod);
		}
		lineNumsOfMethod.put(offset, lineNumber);
	}

	public Map<String, Map<String, TreeMap<Integer, Integer>>> getGeneratedLineNumbers() {
		Map<String, Map<String, TreeMap<Integer, Integer>>> generatedLineNumbers = new HashMap<>();
		for (String typeStr : generatedLineNumbersRaw.keySet()) {
			Map<String, TreeMap<Integer, Integer>> lineNumsOfType = generatedLineNumbersRaw.get(typeStr);

			if (lineNumsOfType != null) {
				Map<String, TreeMap<Integer, Integer>> lineNumsOfTypeCleaned = new HashMap<>();
				for (String methodKey : lineNumsOfType.keySet()) {
					TreeMap<Integer, Integer> lineNumsOfMethod = lineNumsOfType.get(methodKey);

					if (lineNumsOfMethod != null) {
						TreeMap<Integer, Integer> lineNumsOfMethodCleaned = cleanUpLineNumsOfMethod(lineNumsOfMethod);
						if (lineNumsOfMethodCleaned.size() > 0) {
							lineNumsOfTypeCleaned.put(methodKey, lineNumsOfMethodCleaned);
						}
					}

				}
				if (lineNumsOfTypeCleaned.size() > 0) {
					generatedLineNumbers.put(typeStr, lineNumsOfTypeCleaned);
				}
			}

		}
		return generatedLineNumbers;
	}

	private TreeMap<Integer, Integer> cleanUpLineNumsOfMethod(TreeMap<Integer, Integer> lineNumsOfMethod) {
		TreeMap<Integer, Integer> lineNumsOfMethodCleaned = new TreeMap<>();
		Integer lineNumPrev = null;
		for (Integer offset : lineNumsOfMethod.keySet()) {
			Integer lineNum = lineNumsOfMethod.get(offset);
			if (lineNum != null) {
				if (lineNumPrev == null) {
					lineNumsOfMethodCleaned.put(0, lineNum);
				} else if (!lineNum.equals(lineNumPrev)) {
					lineNumsOfMethodCleaned.put(offset, lineNum);
				}
				lineNumPrev = lineNum;
			}
		}
		return lineNumsOfMethodCleaned;
	}
}
