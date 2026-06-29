package org.jdkAnalyzer;

import org.eclipse.jdt.core.dom.*;

import java.util.HashSet;
import java.util.Set;

public class ClassVisitor extends ASTVisitor {

    @Override
    public boolean visit(TypeDeclaration node){
        System.out.println("type name:   " + node.getName().toString());
        String qualifiedName = node.resolveBinding().getQualifiedName();
        // save class
        SQLUtil.insertCLassAndModifierAndType(
                qualifiedName,
                node.getModifiers(),
                getClassType(node));

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
        if (comment.getParent().getNodeType() == ASTNode.TYPE_DECLARATION){
            TypeDeclaration node = (TypeDeclaration)comment.getParent();
            String className = node.resolveBinding().getQualifiedName();
            SQLUtil.updateClassComment(className, comment.toString());
            if (isThrowable(node)){
                SQLUtil.updateExceptionComment(className, comment.toString());
            }
        }
    }
}
