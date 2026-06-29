package org.jdkAnalyzer;

import org.eclipse.jdt.core.dom.*;

import java.util.EmptyStackException;
import java.util.List;
import java.util.Stack;

public class MethodVisitor extends ASTVisitor {
    private MethodDeclaration visitingMethodDeclaration = null;
    private Stack<Expression> ifExpressions = new Stack<>();

    public static String generateMethodQualifiedName(MethodDeclaration node){
        return generateMethodQualifiedName(node.resolveBinding());
    }
    public static String generateMethodQualifiedName(IMethodBinding methodBinding){
        return methodBinding.getDeclaringClass().getQualifiedName() +
                "." +
                generateMethodFullName(methodBinding);
    }
    public static String generateMethodFullName(MethodDeclaration node){
        return generateMethodFullName(node.resolveBinding());
    }
    public static String generateMethodFullName(IMethodBinding methodBinding){
        StringBuilder sb = new StringBuilder();
        sb.append(methodBinding.getName());
        sb.append("(");
        ITypeBinding[] typeBindings = methodBinding.getParameterTypes();
        for (ITypeBinding typeBinding : typeBindings){
            sb.append(typeBinding.getName());
            sb.append(",");
        }
        sb.append(")");
        return sb.toString().replace(",)", ")");
    }

    @Override
    public boolean visit(MethodDeclaration node){
        this.visitingMethodDeclaration = node;
        // save method
        String qualifiedName = generateMethodQualifiedName(node);
        System.out.println("method name: " + qualifiedName);
        SQLUtil.insertMethodAndModifierAndContent(
                qualifiedName,
                node.getModifiers(),
                node.toString()
        );

        // save method belong class
        String className = node.resolveBinding().getDeclaringClass().getQualifiedName();
        SQLUtil.insertBelong(
                qualifiedName,
                className
        );

        // save declaration throws
        List<Type> thrownExceptions = node.thrownExceptionTypes();
        String type = "declaration";
        for (Type exceptionType : thrownExceptions) {
            String exceptionName = exceptionType.resolveBinding().getQualifiedName();
            SQLUtil.insertThrowMethodAndExceptionAndWay(
                    qualifiedName + "-" + exceptionName + "-" + type,
                    qualifiedName,
                    exceptionName,
                    type
            );
        }
        return true;
    }
    @Override
    public void endVisit(MethodDeclaration node){
        this.visitingMethodDeclaration = null;
    }

    @Override
    public boolean visit(MethodInvocation node){
        // in case not in method declaration
        if (this.visitingMethodDeclaration == null){
            return true;
        }

        // save method call
        String declaringMethodQualifiedName = generateMethodQualifiedName(this.visitingMethodDeclaration);
        String calledMethodQualifiedName = generateMethodQualifiedName(node.resolveMethodBinding().getMethodDeclaration());
        SQLUtil.insertCall(
                declaringMethodQualifiedName,
                calledMethodQualifiedName
        );
        return true;
    }
    @Override
    public boolean visit(IfStatement node){
        this.ifExpressions.push(node.getExpression());
        return true;
    }
    @Override
    public void endVisit(IfStatement node){
        this.ifExpressions.pop();
    }

    @Override
    public boolean visit(ThrowStatement node){
        try{
            // if if-expression exist
            String ifExpression = this.ifExpressions.peek().toString();
            String methodName = generateMethodQualifiedName(this.visitingMethodDeclaration);
            String exceptionName = node.getExpression().resolveTypeBinding().getQualifiedName();
            String way = "throw statement";
            SQLUtil.insertThrowMethodAndExceptionAndWayAndCondition(
                    methodName + "-" + exceptionName + "-"+ way,
                    methodName,
                    exceptionName,
                    way,
                    ifExpression
            );
        }catch (EmptyStackException ignored){
            // if if-expression not exist
            String methodName = generateMethodQualifiedName(this.visitingMethodDeclaration);
            String exceptionName = node.getExpression().resolveTypeBinding().getQualifiedName();
            String way = "throw statement";
            SQLUtil.insertThrowMethodAndExceptionAndWay(
                    methodName + "-" + exceptionName + "-"+ way,
                    methodName,
                    exceptionName,
                    way
            );
        }
        return true;
    }

    @Override
    public boolean visit(LineComment node){
        handleComment(node);
        return true;
    }

    @Override
    public boolean visit(BlockComment node){
        handleComment(node);
        return true;
    }

    @Override
    public boolean visit(Javadoc node){
        handleComment(node);
        return true;
    }

    private void handleComment(Comment comment){
        if (comment == null){
            return;
        }
        if (comment.getParent().getNodeType() == ASTNode.METHOD_DECLARATION){
            MethodDeclaration node = (MethodDeclaration)comment.getParent();
            String methodQualifiedName = generateMethodQualifiedName(node);
            SQLUtil.updateMethodComment(methodQualifiedName, comment.toString());
        }
    }
}
