package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

public class MethodVisitor extends ASTVisitor{
	private CompilationUnit fullMethodCU;
	private MethodDeclaration methodDeclaration;
	private String methodBlock;
	private String[] methodLines;
	private List<TryBlock> tryBlocks = new ArrayList<>();
	private int[] methodLineTryNestings;
	private int[] lineBelongs;

	@Override
	public boolean visit(CompilationUnit node) {
		fullMethodCU = node;
		return true;
	}

	@Override
	public boolean visit(Block node){
		
		if (node.getParent() instanceof MethodDeclaration
				&& !(node.getParent().getParent() instanceof AnonymousClassDeclaration)) {
			methodDeclaration = (MethodDeclaration) node.getParent();
//			System.out.println(methodDeclaration.getName().toString());
//			System.out.println(methodDeclaration.thrownExceptionTypes().toString());
//			System.out.println(node.toString());
			methodBlock = node.toString().replaceAll("\\s+$", "");
			methodLines = methodBlock.split("\n");
		}

		
		if (node.getParent() instanceof TryStatement){
			
			TryBlock tb = new TryBlock((TryStatement) node.getParent(), this.methodBlock);
			
			
			if (!tb.isLocated()) {
				return true;
			}
			boolean duplicate = false;
			for (TryBlock tryBlock : tryBlocks) {
				if (tb.equals(tryBlock)) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) {
				this.tryBlocks.add(tb);
			}
		}
		return true;
	}

	@Override
	public void endVisit(CompilationUnit node) {
		
		if (node.toString().isEmpty()) {
			throw new IllegalStateException();
		}
		calculateMethodLineTryNestings();
		calculateTryBlocksSelfLine();
//		show();
	}

	private int countTryNesting(ASTNode node){
		if (node.getParent() == null){
			return 0;
		}
		if (node.getParent() instanceof TryStatement){
			return 1 + countTryNesting(node.getParent());
		} else {
			return countTryNesting(node.getParent());
		}
	}

	private void calculateTryBlocksSelfLine() {
		lineBelongs = new int[methodLines.length];
		Arrays.fill(lineBelongs, -1);
		for (int i = 0; i < tryBlocks.size(); i++) {
			for (int j = tryBlocks.get(i).getFormatStartLine();
				 j < tryBlocks.get(i).getFormatStartLine() + tryBlocks.get(i).getFormatLength();
				 j++) {
				lineBelongs[j] = i;
			}
		}
		for (int i = 0; i < lineBelongs.length; i++) {
			if (lineBelongs[i] != -1) {
				tryBlocks.get(lineBelongs[i]).addSelfLine(i);
			}
		}
	}

	private void calculateMethodLineTryNestings() {
		methodLineTryNestings = new int[methodLines.length];
		Arrays.fill(methodLineTryNestings, 0);
		for (TryBlock tb : this.tryBlocks) {
			for (int i = tb.getFormatStartLine(); i < tb.getFormatStartLine() + tb.getFormatLength(); i++) {
				methodLineTryNestings[i] += 1;
			}
		}
	}

	public String getFullMethod() {
		return fullMethodCU.toString();
	}

	public String getMethodBlock() {
		return methodBlock;
	}

	public List<TryBlock> getTryBlocks() {
		return tryBlocks;
	}

	public MethodDeclaration getMethodDeclaration() {
		return methodDeclaration;
	}

	public int[] getLineBelongs() {
		return lineBelongs;
	}

	public int[] getMethodLineTryNestings() {
		return methodLineTryNestings;
	}

	public void show() {
		System.out.println("------------------------------------------------------------------------");
		System.out.println(methodDeclaration.getName().toString());
		for (int i = 0; i < methodLines.length; i++) {
			System.out.println(String.format("|%3d|%3d|%3d|", i, methodLineTryNestings[i], lineBelongs[i]) + methodLines[i]);
		}
	}
}
