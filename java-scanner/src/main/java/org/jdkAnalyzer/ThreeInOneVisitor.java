package org.jdkAnalyzer;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

public class ThreeInOneVisitor  extends ASTVisitor {

    String filePath = null;

    public ThreeInOneVisitor(String filePath){
        this.filePath = filePath;
    }
    @Override
    public boolean visit(TypeDeclaration node){
        try{
            System.out.println("type name:   " + node.getName().toString());
            String qualifiedName = node.resolveBinding().getQualifiedName();
            // save class
            SQLUtil.insertCLassAndModifierAndType(
                    qualifiedName,
                    node.getModifiers(),
                    getClassType(node));

            SQLUtil.insertFileClass(this.filePath, qualifiedName);

            // save inherits and implementation
            ITypeBinding superClassBinding = node.resolveBinding().getSuperclass();
            if (superClassBinding != null) {
                SQLUtil.insertInherit(
                        qualifiedName,
                        superClassBinding.getQualifiedName(),
                        "Inheritance"
                );
            }
            ITypeBinding[] interfaceBindings = node.resolveBinding().getInterfaces();
            for (ITypeBinding interfaceBinding : interfaceBindings) {
                SQLUtil.insertInherit(
                        qualifiedName,
                        interfaceBinding.getQualifiedName(),
                        "Realization"
                );
            }

            // check inherits and judge whether class is a throwable
            if (isThrowable(node)){
                String type;
                if (isRuntimeException(node)){
                    type = "runtime";
                }
                else {
                    type = "checked";
                }
                String parent;
                if (node.resolveBinding().getQualifiedName().equals("java.lang.Throwable")){
                    parent = "java.lang.Throwable";
                }
                else {
                    parent = node.resolveBinding().getSuperclass().getQualifiedName();
                }
                SQLUtil.insertExceptionAndModifierAndParentAndType(
                        node.resolveBinding().getQualifiedName(),
                        node.getModifiers(),
                        parent,
                        type
                );
            }
        } catch (NullPointerException e){
            System.err.println("failed visit TypeDeclaration node due to NullPointerException.");
        }
        return true;
    }
    private boolean isThrowable(TypeDeclaration node){
        return getSuperclasses(node).contains("java.lang.Throwable");
    }
    private boolean isRuntimeException(TypeDeclaration node){
        return getSuperclasses(node).contains("java.lang.RuntimeException");
    }
    private Set<String> getSuperclasses(TypeDeclaration node){
        Set<String> superClassNames = new HashSet<>();
        ITypeBinding typeBinding = node.resolveBinding();
        superClassNames.add(typeBinding.getQualifiedName());
        while(typeBinding.getSuperclass() != null){
            typeBinding = typeBinding.getSuperclass();
            superClassNames.add(typeBinding.getQualifiedName());
        }
        return superClassNames;
    }
    public String getClassType(TypeDeclaration node){
        if (node.isInterface()){
            return "interface";
        }
        if (Modifier.isAbstract(node.getModifiers())){
            return "abstract class";
        }
        return "class";
    }

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
        try {
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
        } catch (NullPointerException e){
            System.err.println("failed visit MethodDeclaration node due to NullPointerException.");
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
        if (this.visitingMethodDeclaration == null || node == null){
            return true;
        }

        try {
            // save method call
            String declaringMethodQualifiedName = generateMethodQualifiedName(this.visitingMethodDeclaration);
            String calledMethodQualifiedName = generateMethodQualifiedName(node.resolveMethodBinding().getMethodDeclaration());
            SQLUtil.insertCall(
                    declaringMethodQualifiedName,
                    calledMethodQualifiedName
            );
        } catch (NullPointerException e){
            System.err.println("failed record call due to NullPointerException.");
        }
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
            try{
                String methodName = generateMethodQualifiedName(this.visitingMethodDeclaration);
                String exceptionName = node.getExpression().resolveTypeBinding().getQualifiedName();
                String way = "throw statement";
                SQLUtil.insertThrowMethodAndExceptionAndWay(
                        methodName + "-" + exceptionName + "-"+ way,
                        methodName,
                        exceptionName,
                        way
                );
            } catch (NullPointerException e){
                System.err.println("failed record ThrowMethodAndExceptionAndWay due to NullPointerException.");
            }
        }catch (NullPointerException e){
            System.err.println("failed record ThrowMethodAndExceptionAndWay due to NullPointerException.");
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
        try{
            if (comment.getParent().getNodeType() == ASTNode.METHOD_DECLARATION){
                MethodDeclaration node = (MethodDeclaration)comment.getParent();
                String methodQualifiedName = generateMethodQualifiedName(node);
                SQLUtil.updateMethodComment(methodQualifiedName, comment.toString());
            }else if (comment.getParent().getNodeType() == ASTNode.TYPE_DECLARATION){
                TypeDeclaration node = (TypeDeclaration)comment.getParent();
                String className = node.resolveBinding().getQualifiedName();
                SQLUtil.updateClassComment(className, comment.toString());
                if (isThrowable(node)){
                    SQLUtil.updateExceptionComment(className, comment.toString());
                }
            }
        } catch (NullPointerException e){
            System.err.println("failed handle comment due to NullPointerException.");
        }
    }
}
