package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MethodFinder extends ASTVisitor {
    private List<MethodDeclaration> methods = new ArrayList<>();
    @Override
    public boolean visit(MethodDeclaration node) {
        
//        System.out.println("Visiting Method: " + Util.generateMethodName(node));
        methods.add(node);
        return false;
    }

    public List<MethodDeclaration> getMethods() {
        return methods;
    }
}
