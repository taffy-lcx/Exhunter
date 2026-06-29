package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.*;

import java.util.*;


 








public class MethodDeclarationVisitor extends ASTVisitor {
    private IMethodBinding targetMethodBinding;
    private boolean inTargetMethodDeclarationFlag = false;
    private List<ThrowExceptionInfo> throwExceptionInfos = new ArrayList<>();
    private List<List<CatchClause>> catchClauses = new ArrayList<>();
    private int blockDepth = 0;
    private List<Integer> tryBodyStack = new ArrayList<>();
    private MethodDeclaration methodDeclarationNode = null;
    private Map<IMethodBinding, List<TryCatchNestingInfo>> bindingLeafs = new HashMap<>();
    
    private Map<String, String> localExceptions = new HashMap<>();
    private ThrowStatement inThrowStatementNode = null;
    private List<IfStatement> inIfSeatementStack = new ArrayList<>();
    private List<CatchClause> inCatchClauseStack = new ArrayList<>();

    public MethodDeclarationVisitor(IMethodBinding targetMethodBinding) {
        this.targetMethodBinding = targetMethodBinding;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        if (node.resolveBinding() != null) {
            if (Util.generateMethodQualifiedName(node.resolveBinding().getMethodDeclaration())
                    .equals(Util.generateMethodQualifiedName(targetMethodBinding))) {
                
                methodDeclarationNode = node;
                inTargetMethodDeclarationFlag = true;
            }
        }
        return true;
    }

    @Override
    public void endVisit(MethodDeclaration node) {
        if (node.resolveBinding() != null) {
            if (Util.generateMethodQualifiedName(node.resolveBinding().getMethodDeclaration())
                    .equals(Util.generateMethodQualifiedName(targetMethodBinding))) {
                inTargetMethodDeclarationFlag = false;
            }
        }
    }

    @Override
    public boolean visit(ThrowStatement node) {
        if (!inTargetMethodDeclarationFlag) return true;
        inThrowStatementNode = node;
        ITypeBinding typeBinding = node.getExpression().resolveTypeBinding();
        if (typeBinding != null) {
            String name = typeBinding.getQualifiedName();
        }
        return true;
    }

    @Override
    public void endVisit(ThrowStatement node) {
        if (!inTargetMethodDeclarationFlag) return;
        inThrowStatementNode = null;
    }

    @Override
    public boolean visit(IfStatement node) {
        if (!inTargetMethodDeclarationFlag) return true;
        inIfSeatementStack.add(node);
        return true;
    }

    @Override
    public void endVisit(IfStatement node) {
        if (!inTargetMethodDeclarationFlag) return;
        inIfSeatementStack.remove(inIfSeatementStack.size() - 1);
    }

    @Override
    public boolean visit(CatchClause node) {
        if (!inTargetMethodDeclarationFlag) return true;
        inCatchClauseStack.add(node);
        IVariableBinding variableBinding = node.getException().resolveBinding();
        if (variableBinding != null) {
            ITypeBinding exceptionTypeBinding = variableBinding.getType();
            if (exceptionTypeBinding != null) {
                if (!ExceptionCharacteristicManager.isArchived(exceptionTypeBinding.getQualifiedName())) {
                    
                    recordLocalExceptionAndParent(exceptionTypeBinding);
                }
            }
        }
        return true;
    }
    private void recordLocalExceptionAndParent(ITypeBinding exceptionTypeBinding) {
        ITypeBinding exceptionParentTypeBinding = exceptionTypeBinding.getSuperclass();
        if (exceptionParentTypeBinding == null) {
            return;
        }
        localExceptions.put(exceptionTypeBinding.getQualifiedName(), exceptionParentTypeBinding.getQualifiedName());
        if (!ExceptionCharacteristicManager.isArchived(exceptionParentTypeBinding.getQualifiedName())) {
            recordLocalExceptionAndParent(exceptionParentTypeBinding);
        }
    }

    @Override
    public void endVisit(CatchClause node) {
        if (!inTargetMethodDeclarationFlag) return;
        inCatchClauseStack.remove(inCatchClauseStack.size() - 1);
    }

    public boolean visit(ClassInstanceCreation node) {
        if (!inTargetMethodDeclarationFlag) return true;
        if (inThrowStatementNode == null) return true;
        
        IMethodBinding methodBinding = node.resolveConstructorBinding();
        if (methodBinding == null) return true;
        ITypeBinding typeBinding = methodBinding.getDeclaringClass();
        if (typeBinding == null) return true;
        
        recordLocalExceptionAndParent(typeBinding);
        
        IfStatement ifStatement = null;
        if (!inIfSeatementStack.isEmpty()) ifStatement = inIfSeatementStack.get(inIfSeatementStack.size() - 1);
        CatchClause catchClause = null;
        if (!inCatchClauseStack.isEmpty()) catchClause = inCatchClauseStack.get(inCatchClauseStack.size() - 1);
        throwExceptionInfos.add(new ThrowExceptionInfo(
                inThrowStatementNode,
                node,
                ifStatement,
                catchClause
        ));
        return true;
    }

    @Override
    public boolean visit(MethodInvocation node) {
        if (!inTargetMethodDeclarationFlag) return true;
        IMethodBinding methodBinding = node.resolveMethodBinding();
        IMethodBinding methodDeclarationBinding;
        if (methodBinding == null) {
            return true;
        } else {
            methodDeclarationBinding = methodBinding.getMethodDeclaration();
            if (methodDeclarationBinding == null) {
                return true;
            }
        }
        
        Set<CatchClause> nowCatchClauses = getCatchClauseSet();
        if (nowCatchClauses.isEmpty()) {
            if (bindingLeafs.containsKey(methodDeclarationBinding)) {
                bindingLeafs.get(methodDeclarationBinding).add(new TryCatchNestingInfo());
            } else {
                List<TryCatchNestingInfo> tryCatchNestingInfos = new ArrayList<>();
                tryCatchNestingInfos.add(new TryCatchNestingInfo());
                bindingLeafs.put(methodDeclarationBinding, tryCatchNestingInfos);
            }
        } else {
            if (bindingLeafs.containsKey(methodDeclarationBinding)) {
                bindingLeafs.get(methodDeclarationBinding).add(new TryCatchNestingInfo(nowCatchClauses));
            } else {
                List<TryCatchNestingInfo> tryCatchNestingInfos = new ArrayList<>();
                tryCatchNestingInfos.add(new TryCatchNestingInfo(nowCatchClauses));
                bindingLeafs.put(methodDeclarationBinding, tryCatchNestingInfos);
            }

        }
        return true;
    }
    private Set<CatchClause> getCatchClauseSet() {
        Set<CatchClause> nowCatchClauses = new HashSet<>();
        for (List<CatchClause> ccs: catchClauses) {
            nowCatchClauses.addAll(ccs);
        }
        return nowCatchClauses;
    }

    @Override
    public boolean visit(TryStatement node) {
        if (!inTargetMethodDeclarationFlag) return true;
        
        catchClauses.add(node.catchClauses());
        tryBodyStack.add(Integer.valueOf(blockDepth));
        return true;
    }

    @Override
    public boolean visit(Block node) {
        if (!inTargetMethodDeclarationFlag) return true;
        blockDepth += 1;
        return true;
    }

    @Override
    public void endVisit(Block node) {
        if (!inTargetMethodDeclarationFlag) return;
        blockDepth -= 1;
        if (!tryBodyStack.isEmpty() && tryBodyStack.get(tryBodyStack.size() - 1).equals(blockDepth)) {
            tryBodyStack.remove(tryBodyStack.size() - 1);
            catchClauses.remove(catchClauses.size() - 1);
        }
    }

    public MethodDeclaration getMethodDeclarationNode() {
        return methodDeclarationNode;
    }

    public Map<IMethodBinding, List<TryCatchNestingInfo>> getBindingLeafs() {
        return bindingLeafs;
    }

    public Map<String, String> getLocalExceptions() {
        return localExceptions;
    }

    public List<ThrowExceptionInfo> getThrowExceptionInfos() {
        return throwExceptionInfos;
    }
}
