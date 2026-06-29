package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import java.util.List;

public class Util {
    public static String generateMethodName(MethodDeclaration node){
        StringBuilder sb = new StringBuilder();
        sb.append(node.getName().toString());
        sb.append("(");
        List<SingleVariableDeclaration> parameters = node.parameters();
        for (SingleVariableDeclaration parameter : parameters) {
            sb.append(parameter.getType().toString());
            sb.append(",");
        }
        sb.append(")");
        return sb.toString().replace(",)", ")");
    }

    public static String generateMethodName(IMethodBinding methodBinding){
        StringBuilder sb = new StringBuilder();
        sb.append(methodBinding.getName());
        sb.append("(");
        ITypeBinding typeBindings[] = methodBinding.getParameterTypes();
        for (ITypeBinding typeBinding : typeBindings){
            sb.append(typeBinding.getName());
            sb.append(",");
        }
        sb.append(")");
        return sb.toString().replace(",)", ")");
    }

    public static String generateMethodQualifiedName(IMethodBinding methodBinding) {
        return methodBinding.getDeclaringClass().getQualifiedName() + "." + generateMethodName(methodBinding);
    }
}
