package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;

 




public class MethodFinderVisitor extends ASTVisitor {
    private IMethodBinding targetBinding = null;
    private List<IMethodBinding> allMethodBinding = new ArrayList<>();
    private String targetName;

    public MethodFinderVisitor(String targetName) {
        this.targetName = targetName;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        if (node.resolveBinding() == null) {
            return true;
        }
        if (Util.generateMethodQualifiedName(node.resolveBinding().getMethodDeclaration()).endsWith(targetName)) {
            targetBinding = node.resolveBinding().getMethodDeclaration();
        } else {
            IMethodBinding binding = node.resolveBinding().getMethodDeclaration();
            if (binding != null){
                allMethodBinding.add(binding);
            }
        }
        return true;
    }

    public IMethodBinding getTargetBinding() {
        return targetBinding;
    }

    public List<IMethodBinding> getAllMethodBinding() {
        return allMethodBinding;
    }
}
