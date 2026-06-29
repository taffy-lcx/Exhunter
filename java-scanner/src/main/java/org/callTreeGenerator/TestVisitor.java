package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;

@Deprecated
public class TestVisitor extends ASTVisitor {
    public IMethodBinding mb;

    @Override
    public boolean visit(MethodDeclaration node) {
        if (node.getName().toString().equals("processTokens")) {
            mb = node.resolveBinding().getMethodDeclaration();
        }
        return true;
    }
}
