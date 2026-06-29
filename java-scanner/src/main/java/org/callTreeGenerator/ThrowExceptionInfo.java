package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.*;

public class ThrowExceptionInfo {
    private ThrowStatement throwStatementNode;
    private ClassInstanceCreation classInstanceCreationNode;
    private String exceptionQualifiedName;
    private IfStatement ifStatementNode = null;
    private CatchClause catchClauseNode = null;

    public ThrowExceptionInfo(ThrowStatement throwStatementNode,  ClassInstanceCreation classInstanceCreationNode, IfStatement ifStatementNode, CatchClause catchClauseNode) {
        this.throwStatementNode = throwStatementNode;
        this.classInstanceCreationNode = classInstanceCreationNode;
        this.ifStatementNode = ifStatementNode;
        this.catchClauseNode = catchClauseNode;
        IMethodBinding constructorBinding = classInstanceCreationNode.resolveConstructorBinding();
        if (constructorBinding != null) {
            this.exceptionQualifiedName = constructorBinding.getDeclaringClass().getQualifiedName();
        } else {
            this.exceptionQualifiedName = "NO_BINDING";
        }
    }

    public String getThrowStatementString() {
        return throwStatementNode.toString();
    }

    public String getExceptionQualifiedName() {
        return exceptionQualifiedName;
    }

    public String toString() {
        return throwStatementNode.toString();
    }
}
