package org.callTreeGenerator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

 




public class UncaughtExceptionInfoAPI extends UncaughtExceptionInfo {

    public UncaughtExceptionInfoAPI(MethodTreeNode methodTreeNode, List<MethodTreeNode> nodeRoute, Set<String> uncaughtExceptions) {
        super(methodTreeNode, nodeRoute, uncaughtExceptions);
    }

    public String getJavaDoc() {
        return ((ArchivedMethodTreeNode)methodTreeNode).getJavaDoc();
    }

    public String toString() {
        Map<String, String> exceptions = new HashMap<>();
        for (String ex: uncaughtExceptions) {
            exceptions.put(ex, ((ArchivedMethodTreeNode)methodTreeNode).getExceptions().get(ex));
        }
        return "method: " + methodTreeNode.getQualifiedName() + "\nroute:\n    " + nodeRoute.toString() + "\nexceptions:\n" + exceptionDisplayString(exceptions, 4);
//        return "route: " + nodeRoute.toString() + ", exception: " + uncaughtExceptions.toString();
    }
    public String exceptionDisplayString(Map<String, String> exceptions, int offset) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry: exceptions.entrySet()) {
            sb.append(" ".repeat(offset));
            sb.append(entry.getKey());
            sb.append(":  ");
            sb.append(entry.getValue());
            sb.append("\n");
        }
//        if (sb.length() > 0 && (sb.charAt(sb.length() - 1) == '\n' || sb.charAt(sb.length() - 1) == '\r')) {

//        }
        return sb.toString();
    }

}
