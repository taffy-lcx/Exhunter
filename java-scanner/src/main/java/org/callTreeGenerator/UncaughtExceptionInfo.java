package org.callTreeGenerator;

import org.ExperimentExecutor.ExampleHandler;

import java.util.List;
import java.util.Set;

 






public class UncaughtExceptionInfo {
    protected MethodTreeNode methodTreeNode;
    protected List<MethodTreeNode> nodeRoute;
    protected Set<String> uncaughtExceptions; 
    protected Set<String> checkedUncaughtExceptions = null; 
    protected java.util.Map<String, Double> checkedExceptionScores = new java.util.HashMap<>(); 
    protected String description = null; 

    public UncaughtExceptionInfo(MethodTreeNode methodTreeNode, List<MethodTreeNode> nodeRoute, Set<String> uncaughtExceptions) {
        this.methodTreeNode = methodTreeNode;
        this.nodeRoute = nodeRoute;
        this.uncaughtExceptions = uncaughtExceptions;
    }

    public MethodTreeNode getMethodTreeNode() {
        return methodTreeNode;
    }

    public List<MethodTreeNode> getNodeRoute() {
        return nodeRoute;
    }

    public Set<String> getUncaughtExceptions() {
        return uncaughtExceptions;
    }

    public Set<String> getCheckedUncaughtExceptions() {
        return checkedUncaughtExceptions;
    }

    public void setCheckedUncaughtExceptions(Set<String> checkedUncaughtExceptions) {
        this.checkedUncaughtExceptions = checkedUncaughtExceptions;
    }

    public boolean removeCheckedUncaughtException(String checkedUncaughtException) {
        if (this.checkedUncaughtExceptions.contains(checkedUncaughtException)) {
            checkedUncaughtExceptions.remove(checkedUncaughtException);
            return true;
        } else {
            return false;
        }
    }

    public MethodTreeNode getSecondLastNode() {
        if (nodeRoute.size() < 2) {
            throw new RuntimeException("UncaughtExceptionInfo node route size shouldn't less than 2");
        }
        return nodeRoute.get(nodeRoute.size() - 2);
    }

    public String getUncaughtExceptionsString() {
        StringBuilder sb = new StringBuilder();
        for (String e: uncaughtExceptions) {
            sb.append(e).append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }

     



    public String getCheckedUncaughtExceptionsString() {
        if (checkedUncaughtExceptions.isEmpty()) return getUncaughtExceptionsString();
        StringBuilder sb = new StringBuilder();
        for (String e: checkedUncaughtExceptions) {
            sb.append(e).append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }

    public void setCheckedExceptionScores(java.util.Map<String, Double> scores) {
        this.checkedExceptionScores = (scores == null) ? new java.util.HashMap<>() : scores;
    }

    public java.util.Map<String, Double> getCheckedExceptionScores() {
        return checkedExceptionScores;
    }

     




    public String getCheckedUncaughtExceptionsScoredString() {
        Set<String> shown = (checkedUncaughtExceptions == null || checkedUncaughtExceptions.isEmpty())
                ? uncaughtExceptions : checkedUncaughtExceptions;
        StringBuilder sb = new StringBuilder();
        for (String e: shown) {
            sb.append(e);
            Double s = checkedExceptionScores.get(e);
            if (s != null) {
                sb.append(" (score ").append(s).append(")");
            }
            sb.append(", ");
        }
        return sb.length() >= 2 ? sb.substring(0, sb.length() - 2) : sb.toString();
    }

    public boolean hasDescription() {
        return (!(description == null));
    }
    public String getDescription() {
        if (!hasDescription()) {
            return "";
        }
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

     



    public Boolean isContainUncaughtExceptions() {
        if (checkedUncaughtExceptions == null) {
            return (!uncaughtExceptions.isEmpty());
        } else {
            return (!checkedUncaughtExceptions.isEmpty());
        }
    }

    public String getHash() {
        String representation = new StringBuilder()
                .append(methodTreeNode.toString())
                .append(nodeRoute.toString())
                .append(uncaughtExceptions.toString())
                .toString();
        return ExampleHandler.md5Hash(representation);
    }

}
