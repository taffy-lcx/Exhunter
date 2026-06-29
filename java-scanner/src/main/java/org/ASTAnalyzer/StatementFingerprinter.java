package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

 







public final class StatementFingerprinter {

    private StatementFingerprinter() {}

    
    
    
    

     




    public static Map<String, Integer> collectMethodFingerprints(MethodDeclaration md) {
        Map<String, Integer> result = new HashMap<>();
        if (md == null || md.getBody() == null) return result;
        FullCollector collector = new FullCollector(result);
        md.getBody().accept(collector);
        return result;
    }

     


    public static Set<String> collectTryBodyFingerprints(TryStatement ts) {
        Set<String> result = new HashSet<>();
        if (ts == null || ts.getBody() == null) return result;
        TryBodyCollector collector = new TryBodyCollector(result);
        ts.getBody().accept(collector);
        return result;
    }

     





    public static Set<String> collectAllTryBodyFingerprints(MethodDeclaration md) {
        Set<String> result = new HashSet<>();
        if (md == null || md.getBody() == null) return result;
        md.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(TryStatement node) {
                result.addAll(collectTryBodyFingerprints(node));
                return true; 
            }
        });
        return result;
    }

    
    
    
    

    public static List<String> collectMethodFingerprintsList(MethodDeclaration md) {
        List<String> result = new ArrayList<>();
        if (md == null || md.getBody() == null) return result;
        md.getBody().accept(new FullCollectorList(result));
        return result;
    }

    public static List<String> collectTryBodyFingerprintsList(TryStatement ts) {
        List<String> result = new ArrayList<>();
        if (ts == null || ts.getBody() == null) return result;
        ts.getBody().accept(new TryBodyCollectorList(result));
        return result;
    }

    public static List<String> collectAllTryBodyFingerprintsList(MethodDeclaration md) {
        List<String> result = new ArrayList<>();
        if (md == null || md.getBody() == null) return result;
        md.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(TryStatement node) {
                result.addAll(collectTryBodyFingerprintsList(node));
                return true; 
            }
        });
        return result;
    }

    

    static String fpOfMethodInvocation(MethodInvocation mi) {
        if (mi.getName() == null) return null;
        String name = mi.getName().getIdentifier();
        if (name == null || name.isEmpty()) return null;
        String recv = receiverKind(mi.getExpression());
        String args = argKindSeq(mi.arguments());
        return "call:" + recv + "." + name + ":" + args;
    }

    static String fpOfSuperMethodInvocation(SuperMethodInvocation smi) {
        if (smi.getName() == null) return null;
        String name = smi.getName().getIdentifier();
        if (name == null || name.isEmpty()) return null;
        return "call:super." + name + ":" + argKindSeq(smi.arguments());
    }

    static String fpOfClassInstanceCreation(ClassInstanceCreation cic) {
        Type t = cic.getType();
        String typeStr = (t == null) ? "?" : t.toString();
        return "new:" + typeStr + ":" + argKindSeq(cic.arguments());
    }

    static String receiverKind(Expression e) {
        if (e == null) return "this";
        if (e instanceof ThisExpression) return "this";
        if (e instanceof SuperFieldAccess) return "super";
        if (e instanceof SimpleName) {
            String n = ((SimpleName) e).getIdentifier();
            
            if (!n.isEmpty() && Character.isUpperCase(n.charAt(0))) return "type";
            return "name";
        }
        if (e instanceof FieldAccess) return "field";
        if (e instanceof QualifiedName) return "qname";
        if (e instanceof MethodInvocation) return "call";
        if (e instanceof ClassInstanceCreation) return "new";
        if (e instanceof CastExpression) return "cast";
        if (e instanceof ParenthesizedExpression) return "paren";
        return "expr";
    }

    static String argKindSeq(java.util.List<?> args) {
        if (args == null || args.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(",");
            Object a = args.get(i);
            sb.append(a instanceof Expression ? exprKind((Expression) a) : "?");
        }
        sb.append("]");
        return sb.toString();
    }

    static String fpOfThrow(ThrowStatement ts) {
        Expression e = ts.getExpression();
        if (e instanceof ClassInstanceCreation) {
            Type t = ((ClassInstanceCreation) e).getType();
            if (t != null) return "throw:" + t.toString();
        }
        return null;
    }

    static String fpOfReturn(ReturnStatement rs) {
        Expression e = rs.getExpression();
        if (e == null) return "ret:void";
        if (e instanceof MethodInvocation) {
            String inner = fpOfMethodInvocation((MethodInvocation) e);
            
            return inner == null ? "ret:call" : "ret:" + inner;
        }
        return "ret:" + exprKind(e);
    }

    
    
    
    
    

    static String fpOfAssignmentStmt(ExpressionStatement es) {
        if (!(es.getExpression() instanceof Assignment)) return null;
        Assignment a = (Assignment) es.getExpression();
        String lhs = exprKind(a.getLeftHandSide());
        Expression rhs = a.getRightHandSide();
        if (rhs instanceof MethodInvocation) {
            String inner = fpOfMethodInvocation((MethodInvocation) rhs);
            return "assign:" + lhs + ":" + (inner == null ? "call" : inner);
        }
        if (rhs instanceof ClassInstanceCreation) {
            return "assign:" + lhs + ":" + fpOfClassInstanceCreation((ClassInstanceCreation) rhs);
        }
        return "assign:" + lhs + ":" + exprKind(rhs);
    }

    
    
    static String fpOfNonAssignExprStmt(ExpressionStatement es) {
        Expression e = es.getExpression();
        if (e instanceof Assignment) return null; 
        if (e instanceof PrefixExpression) {
            PrefixExpression pe = (PrefixExpression) e;
            return "prefix:" + pe.getOperator().toString() + ":" + exprKind(pe.getOperand());
        }
        if (e instanceof PostfixExpression) {
            PostfixExpression pe = (PostfixExpression) e;
            return "postfix:" + pe.getOperator().toString() + ":" + exprKind(pe.getOperand());
        }
        
        return null;
    }

    static String fpOfForStmt(ForStatement fs) {
        String init = fs.initializers().isEmpty() ? "" :
                (fs.initializers().get(0) instanceof Expression
                 ? exprKind((Expression) fs.initializers().get(0))
                 : "decl");
        String cond = fs.getExpression() == null ? "" : exprKind(fs.getExpression());
        String upd  = fs.updaters().isEmpty() ? "" : exprKind((Expression) fs.updaters().get(0));
        return "for:" + init + ":" + cond + ":" + upd;
    }

    static String fpOfEnhancedFor(EnhancedForStatement efs) {
        Type t = efs.getParameter() == null ? null : efs.getParameter().getType();
        String type = (t == null) ? "?" : t.toString();
        return "enhanced_for:" + type + ":" + exprKind(efs.getExpression());
    }

    static String fpOfWhile(WhileStatement ws) {
        return "while:" + exprKind(ws.getExpression());
    }

    static String fpOfDo(DoStatement ds) {
        return "do:" + exprKind(ds.getExpression());
    }

    static String fpOfSwitch(SwitchStatement ss) {
        return "switch:" + exprKind(ss.getExpression());
    }

    static String fpOfSync(SynchronizedStatement ss) {
        return "sync:" + exprKind(ss.getExpression());
    }

    static String fpOfSuperCtor(SuperConstructorInvocation sci) {
        return "super_ctor:" + argKindSeq(sci.arguments());
    }

    static String fpOfThisCtor(ConstructorInvocation ci) {
        return "this_ctor:" + argKindSeq(ci.arguments());
    }

    static String fpOfVarDecl(VariableDeclarationStatement vds) {
        Type t = vds.getType();
        String type = (t == null) ? "?" : t.toString();
        
        if (vds.fragments().isEmpty()) return null;
        VariableDeclarationFragment frag = (VariableDeclarationFragment) vds.fragments().get(0);
        Expression init = frag.getInitializer();
        if (init == null) return "decl:" + type + ":uninit";
        if (init instanceof MethodInvocation) {
            String inner = fpOfMethodInvocation((MethodInvocation) init);
            return "decl:" + type + ":" + (inner == null ? "call" : inner);
        }
        if (init instanceof ClassInstanceCreation) {
            return "decl:" + type + ":" + fpOfClassInstanceCreation((ClassInstanceCreation) init);
        }
        return "decl:" + type + ":" + exprKind(init);
    }

    static String fpOfIf(IfStatement is) {
        Expression cond = is.getExpression();
        if (cond == null) return "if:?";
        if (cond instanceof MethodInvocation) {
            String inner = fpOfMethodInvocation((MethodInvocation) cond);
            return "if:" + (inner == null ? "call" : inner);
        }
        if (cond instanceof InfixExpression) {
            InfixExpression ie = (InfixExpression) cond;
            return "if:" + exprKind(ie.getLeftOperand()) + ie.getOperator().toString() + exprKind(ie.getRightOperand());
        }
        if (cond instanceof PrefixExpression) {
            PrefixExpression pe = (PrefixExpression) cond;
            return "if:" + pe.getOperator().toString() + exprKind(pe.getOperand());
        }
        return "if:" + exprKind(cond);
    }

    static String exprKind(Expression e) {
        if (e == null) return "null";
        if (e instanceof MethodInvocation) return "call";
        if (e instanceof ClassInstanceCreation) return "new";
        if (e instanceof NumberLiteral) return "num";
        if (e instanceof StringLiteral) return "str";
        if (e instanceof BooleanLiteral) return "bool";
        if (e instanceof CharacterLiteral) return "char";
        if (e instanceof NullLiteral) return "null";
        if (e instanceof SimpleName) return "id";
        if (e instanceof FieldAccess) return "field";
        if (e instanceof InfixExpression) return "binop";
        if (e instanceof PrefixExpression) return "preop";
        if (e instanceof CastExpression) return "cast";
        return e.getClass().getSimpleName();
    }

    

    static int tryNestingOf(ASTNode node) {
        int depth = 0;
        ASTNode p = node.getParent();
        while (p != null) {
            if (p instanceof TryStatement) depth++;
            p = p.getParent();
        }
        return depth;
    }

    

    private static class FullCollector extends ASTVisitor {
        private final Map<String, Integer> map;
        FullCollector(Map<String, Integer> map) { this.map = map; }

        @Override
        public boolean visit(CatchClause node) { return false; }

        @Override
        public boolean visit(TryStatement node) {
            
            
            if (node.getBody() != null) node.getBody().accept(this);
            
            return false;
        }

        @Override
        public boolean visit(MethodInvocation node) {
            record(fpOfMethodInvocation(node), node);
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            record(fpOfClassInstanceCreation(node), node);
            return true;
        }

        @Override
        public boolean visit(ThrowStatement node) {
            record(fpOfThrow(node), node);
            return true;
        }

        @Override
        public boolean visit(ReturnStatement node) {
            record(fpOfReturn(node), node);
            return true;
        }

        @Override
        public boolean visit(ExpressionStatement node) {
            record(fpOfAssignmentStmt(node), node);
            record(fpOfNonAssignExprStmt(node), node);
            return true;
        }

        @Override
        public boolean visit(VariableDeclarationStatement node) {
            record(fpOfVarDecl(node), node);
            return true;
        }

        @Override
        public boolean visit(IfStatement node) {
            record(fpOfIf(node), node);
            return true;
        }

        @Override
        public boolean visit(ForStatement node) { record(fpOfForStmt(node), node); return true; }

        @Override
        public boolean visit(EnhancedForStatement node) { record(fpOfEnhancedFor(node), node); return true; }

        @Override
        public boolean visit(WhileStatement node) { record(fpOfWhile(node), node); return true; }

        @Override
        public boolean visit(DoStatement node) { record(fpOfDo(node), node); return true; }

        @Override
        public boolean visit(SwitchStatement node) { record(fpOfSwitch(node), node); return true; }

        @Override
        public boolean visit(SynchronizedStatement node) { record(fpOfSync(node), node); return true; }

        @Override
        public boolean visit(SuperConstructorInvocation node) { record(fpOfSuperCtor(node), node); return true; }

        @Override
        public boolean visit(ConstructorInvocation node) { record(fpOfThisCtor(node), node); return true; }

        @Override
        public boolean visit(SuperMethodInvocation node) { record(fpOfSuperMethodInvocation(node), node); return true; }

        private void record(String fp, ASTNode node) {
            if (fp == null) return;
            int d = tryNestingOf(node);
            Integer existing = map.get(fp);
            
            if (existing == null || d > existing) {
                map.put(fp, d);
            }
        }
    }

    

    private static class TryBodyCollector extends ASTVisitor {
        private final Set<String> fps;
        TryBodyCollector(Set<String> fps) { this.fps = fps; }

        @Override
        public boolean visit(TryStatement node) { return false; }

        @Override
        public boolean visit(MethodInvocation node) {
            add(fpOfMethodInvocation(node));
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            add(fpOfClassInstanceCreation(node));
            return true;
        }

        @Override
        public boolean visit(ThrowStatement node) {
            add(fpOfThrow(node));
            return true;
        }

        @Override
        public boolean visit(ReturnStatement node) {
            add(fpOfReturn(node));
            return true;
        }

        @Override
        public boolean visit(ExpressionStatement node) {
            add(fpOfAssignmentStmt(node));
            add(fpOfNonAssignExprStmt(node));
            return true;
        }

        @Override
        public boolean visit(VariableDeclarationStatement node) {
            add(fpOfVarDecl(node));
            return true;
        }

        @Override
        public boolean visit(IfStatement node) {
            add(fpOfIf(node));
            return true;
        }

        @Override
        public boolean visit(ForStatement node) { add(fpOfForStmt(node)); return true; }
        @Override
        public boolean visit(EnhancedForStatement node) { add(fpOfEnhancedFor(node)); return true; }
        @Override
        public boolean visit(WhileStatement node) { add(fpOfWhile(node)); return true; }
        @Override
        public boolean visit(DoStatement node) { add(fpOfDo(node)); return true; }
        @Override
        public boolean visit(SwitchStatement node) { add(fpOfSwitch(node)); return true; }
        @Override
        public boolean visit(SynchronizedStatement node) { add(fpOfSync(node)); return true; }
        @Override
        public boolean visit(SuperConstructorInvocation node) { add(fpOfSuperCtor(node)); return true; }
        @Override
        public boolean visit(ConstructorInvocation node) { add(fpOfThisCtor(node)); return true; }
        @Override
        public boolean visit(SuperMethodInvocation node) { add(fpOfSuperMethodInvocation(node)); return true; }

        private void add(String fp) {
            if (fp != null) fps.add(fp);
        }
    }

    

    private static class FullCollectorList extends ASTVisitor {
        private final List<String> list;
        FullCollectorList(List<String> list) { this.list = list; }

        @Override public boolean visit(CatchClause node) { return false; }

        @Override
        public boolean visit(TryStatement node) {
            if (node.getBody() != null) node.getBody().accept(this);
            return false; 
        }

        @Override public boolean visit(MethodInvocation node) { add(fpOfMethodInvocation(node)); return true; }
        @Override public boolean visit(SuperMethodInvocation node) { add(fpOfSuperMethodInvocation(node)); return true; }
        @Override public boolean visit(ClassInstanceCreation node) { add(fpOfClassInstanceCreation(node)); return true; }
        @Override public boolean visit(ThrowStatement node) { add(fpOfThrow(node)); return true; }
        @Override public boolean visit(ReturnStatement node) { add(fpOfReturn(node)); return true; }
        @Override public boolean visit(ExpressionStatement node) {
            add(fpOfAssignmentStmt(node));
            add(fpOfNonAssignExprStmt(node));
            return true;
        }
        @Override public boolean visit(VariableDeclarationStatement node) { add(fpOfVarDecl(node)); return true; }
        @Override public boolean visit(IfStatement node) { add(fpOfIf(node)); return true; }
        @Override public boolean visit(ForStatement node) { add(fpOfForStmt(node)); return true; }
        @Override public boolean visit(EnhancedForStatement node) { add(fpOfEnhancedFor(node)); return true; }
        @Override public boolean visit(WhileStatement node) { add(fpOfWhile(node)); return true; }
        @Override public boolean visit(DoStatement node) { add(fpOfDo(node)); return true; }
        @Override public boolean visit(SwitchStatement node) { add(fpOfSwitch(node)); return true; }
        @Override public boolean visit(SynchronizedStatement node) { add(fpOfSync(node)); return true; }
        @Override public boolean visit(SuperConstructorInvocation node) { add(fpOfSuperCtor(node)); return true; }
        @Override public boolean visit(ConstructorInvocation node) { add(fpOfThisCtor(node)); return true; }

        private void add(String fp) { if (fp != null) list.add(fp); }
    }

    private static class TryBodyCollectorList extends ASTVisitor {
        private final List<String> list;
        TryBodyCollectorList(List<String> list) { this.list = list; }

        @Override public boolean visit(TryStatement node) { return false; } 

        @Override public boolean visit(MethodInvocation node) { add(fpOfMethodInvocation(node)); return true; }
        @Override public boolean visit(SuperMethodInvocation node) { add(fpOfSuperMethodInvocation(node)); return true; }
        @Override public boolean visit(ClassInstanceCreation node) { add(fpOfClassInstanceCreation(node)); return true; }
        @Override public boolean visit(ThrowStatement node) { add(fpOfThrow(node)); return true; }
        @Override public boolean visit(ReturnStatement node) { add(fpOfReturn(node)); return true; }
        @Override public boolean visit(ExpressionStatement node) {
            add(fpOfAssignmentStmt(node));
            add(fpOfNonAssignExprStmt(node));
            return true;
        }
        @Override public boolean visit(VariableDeclarationStatement node) { add(fpOfVarDecl(node)); return true; }
        @Override public boolean visit(IfStatement node) { add(fpOfIf(node)); return true; }
        @Override public boolean visit(ForStatement node) { add(fpOfForStmt(node)); return true; }
        @Override public boolean visit(EnhancedForStatement node) { add(fpOfEnhancedFor(node)); return true; }
        @Override public boolean visit(WhileStatement node) { add(fpOfWhile(node)); return true; }
        @Override public boolean visit(DoStatement node) { add(fpOfDo(node)); return true; }
        @Override public boolean visit(SwitchStatement node) { add(fpOfSwitch(node)); return true; }
        @Override public boolean visit(SynchronizedStatement node) { add(fpOfSync(node)); return true; }
        @Override public boolean visit(SuperConstructorInvocation node) { add(fpOfSuperCtor(node)); return true; }
        @Override public boolean visit(ConstructorInvocation node) { add(fpOfThisCtor(node)); return true; }

        private void add(String fp) { if (fp != null) list.add(fp); }
    }
}
