package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

public class MethodTreeNode {
    
    protected MethodDeclaration node = null;
    
    protected IMethodBinding binding;
    protected List<ThrowExceptionInfo> throwExceptionInfo = new ArrayList<>();

    public MethodTreeNode() {}
    public MethodTreeNode(IMethodBinding binding) {
        this.binding = binding;
    }

    public MethodDeclaration getNode() {
        return node;
    }

    public IMethodBinding getBinding() {
        return binding;
    }

    public void setNode(MethodDeclaration node) {
        this.node = node;
    }

    public List<ThrowExceptionInfo> getThrowExceptionInfo() {
        return throwExceptionInfo;
    }

    public void setThrowExceptionInfo(List<ThrowExceptionInfo> throwExceptionInfo) {
        this.throwExceptionInfo = throwExceptionInfo;
    }

    public String getQualifiedName() {
        return Util.generateMethodQualifiedName(binding);
    }

    public String toString() {
        return getQualifiedName();
    }

    public String getSimpleName() {
        return binding.getName();
    }

    public String getCode() {
        if (node == null) {
            return "";
        }
        return node.toString();
    }

     




    public String getClassContextSummary() {
        if (node == null || node.getParent() == null) return "";
        ASTNode parent = node.getParent();
        if (!(parent instanceof TypeDeclaration) && !(parent instanceof AnonymousClassDeclaration)) return "";
        StringBuilder sb = new StringBuilder();
        if (parent instanceof TypeDeclaration) {
            TypeDeclaration td = (TypeDeclaration) parent;
            
            sb.append("class ").append(td.getName()).append(" ");
            if (td.getSuperclassType() != null) sb.append("extends ").append(td.getSuperclassType()).append(" ");
            List<?> ifaces = td.superInterfaceTypes();
            if (!ifaces.isEmpty()) sb.append("implements ").append(ifaces).append(" ");
            sb.append("{\n");
            
            for (FieldDeclaration f : td.getFields()) {
                String fs = f.toString().trim();
                
                fs = fs.replaceAll("\\s+", " ");
                if (fs.length() > 200) fs = fs.substring(0, 200) + "...";
                sb.append("  ").append(fs).append("\n");
            }
            
            for (MethodDeclaration m : td.getMethods()) {
                if (m == node) continue;  
                StringBuilder sig = new StringBuilder();
                // modifiers + return type + name + params + throws
                for (Object mod : m.modifiers()) sig.append(mod).append(" ");
                if (m.getReturnType2() != null) sig.append(m.getReturnType2()).append(" ");
                sig.append(m.getName()).append("(");
                List<?> ps = m.parameters();
                for (int i = 0; i < ps.size(); i++) {
                    if (i > 0) sig.append(", ");
                    Object p = ps.get(i);
                    if (p instanceof SingleVariableDeclaration) {
                        SingleVariableDeclaration svd = (SingleVariableDeclaration) p;
                        sig.append(svd.getType()).append(" ").append(svd.getName());
                    }
                }
                sig.append(")");
                List<?> thr = m.thrownExceptionTypes();
                if (!thr.isEmpty()) sig.append(" throws ").append(thr);
                sig.append(";");
                String line = sig.toString().replaceAll("\\s+", " ");
                if (line.length() > 200) line = line.substring(0, 200) + "...";
                sb.append("  ").append(line).append("\n");
            }
            sb.append("}\n");
        }
        return sb.toString();
    }

}
