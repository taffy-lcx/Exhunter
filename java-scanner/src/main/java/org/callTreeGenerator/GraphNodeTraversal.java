package org.callTreeGenerator;

import org.jdkAnalyzer.SQLUtil;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GraphNodeTraversal {
    private static final Logger LOGGER = Logger.getLogger("MAIN");

    private ExceptionCharacteristicManager exceptionCharacteristicManager;

    public GraphNodeTraversal(ExceptionCharacteristicManager exceptionCharacteristicManager) {
        this.exceptionCharacteristicManager = exceptionCharacteristicManager;
    }

    public List<UncaughtExceptionInfo> getSuspiciousThrows(DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph, MethodTreeNode root) {
        return getSuspiciousThrows(graph, root, false);
    }

     




    public List<UncaughtExceptionInfo> getSuspiciousThrows(DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph, MethodTreeNode root, boolean llmUncaught) {
        LOGGER.info("start dfs graph traversal" + (llmUncaught ? " (llm-uncaught: keep all candidates, no static catch-filter)" : ""));
        return dfs(graph, root, new ArrayList<>(), new HashMap<>(), new ArrayList<>(), llmUncaught);
    }

    
    private List<UncaughtExceptionInfo> dfs(
            DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph,
            MethodTreeNode currentNode,
            List<MethodTreeNode> currentPath,
            Map<MethodTreeNode, UncaughtExceptionInfo> visitedNodes,
            List<UncaughtExceptionInfo> uncaughtExceptions,
            boolean llmUncaught) {
        
        currentPath.add(currentNode);
        
        LOGGER.finer("current path: " + currentPath);
        String trueName = SQLUtil.getMethodNameIfExist(currentNode.getQualifiedName());
        
        if (visitedNodes.containsKey(currentNode)) {
            if (trueName != null) {
                
                ArchivedMethodTreeNode archivedMethodTreeNode = (ArchivedMethodTreeNode) visitedNodes.get(currentNode).getMethodTreeNode();
                Set<String> nodeExceptions = archivedMethodTreeNode.getExceptions().keySet();
                Set<String> nodeUncaughtExceptions = llmUncaught ? new HashSet<>(nodeExceptions) : getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                UncaughtExceptionInfoAPI uncaughtExceptionInfo = new UncaughtExceptionInfoAPI(archivedMethodTreeNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                if (!nodeUncaughtExceptions.isEmpty()) {
                    uncaughtExceptions.add(uncaughtExceptionInfo);
                    LOGGER.fine("record archived API node:\n" + uncaughtExceptionInfo.toString());
                }
            } else if (!currentNode.getThrowExceptionInfo().isEmpty()) {
                
                Set<String> nodeExceptions = currentNode.getThrowExceptionInfo().stream()
                        .map(ThrowExceptionInfo::getExceptionQualifiedName)
                        .collect(Collectors.toSet());
                Set<String> nodeUncaughtExceptions = llmUncaught ? new HashSet<>(nodeExceptions) : getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                UncaughtExceptionInfoThrow uncaughtExceptionInfo = new UncaughtExceptionInfoThrow(currentNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                if (!nodeUncaughtExceptions.isEmpty()) {
                    uncaughtExceptions.add(uncaughtExceptionInfo);
                    LOGGER.fine("record archived throw node:\n" + uncaughtExceptionInfo.toString());
                }
            }
        } else {
            
            if (trueName != null) {
                
                ArchivedMethodTreeNode archivedMethodTreeNode = null;
                try{
                    archivedMethodTreeNode = new ArchivedMethodTreeNode(currentNode);
                    archivedMethodTreeNode.makeExceptionNameQualified(exceptionCharacteristicManager);
                } catch (SQLException e) {
                    // ignore
                }
                if (archivedMethodTreeNode != null) {
                    Set<String> nodeExceptions = archivedMethodTreeNode.getExceptions().keySet();
                    Set<String> nodeUncaughtExceptions = llmUncaught ? new HashSet<>(nodeExceptions) : getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                    UncaughtExceptionInfoAPI uncaughtExceptionInfo = new UncaughtExceptionInfoAPI(archivedMethodTreeNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                    visitedNodes.put(currentNode, uncaughtExceptionInfo);
                    if (!nodeUncaughtExceptions.isEmpty()) {
                        uncaughtExceptions.add(uncaughtExceptionInfo);
                        LOGGER.fine("record new API node:\n" + uncaughtExceptionInfo.toString());
                    }
                }
            } else {
                
                Set<String> nodeExceptions = currentNode.getThrowExceptionInfo().stream()
                        .map(ThrowExceptionInfo::getExceptionQualifiedName)
                        .collect(Collectors.toSet());
                Set<String> nodeUncaughtExceptions = llmUncaught ? new HashSet<>(nodeExceptions) : getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                UncaughtExceptionInfoThrow uncaughtExceptionInfo = new UncaughtExceptionInfoThrow(currentNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                visitedNodes.put(currentNode, uncaughtExceptionInfo);
                if (!nodeUncaughtExceptions.isEmpty()) {
                    uncaughtExceptions.add(uncaughtExceptionInfo);
                    LOGGER.fine("record new throw node:\n" + uncaughtExceptionInfo.toString());
                }
                
                for (MethodCallEdge edge : graph.outgoingEdgesOf(currentNode)) {
                    MethodTreeNode targetNode = graph.getEdgeTarget(edge);
                    
                    if (!currentPath.contains(targetNode)) {
                        dfs(graph, targetNode, currentPath, visitedNodes, uncaughtExceptions, llmUncaught);
                    }
                }
            }
        }
        
        currentPath.remove(currentPath.size() - 1);

        return uncaughtExceptions;
    }
    private Set<String> getUncaughtExceptionsOnGraph(
            DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph,
            List<MethodTreeNode> nodeRoute,
            Set<String> exceptions) {
        Set<String> uncaughtExceptions = new HashSet<>(exceptions);
        for (int i = 0; i < nodeRoute.size() - 1; i++) {
            MethodCallEdge edge = graph.getEdge(nodeRoute.get(i), nodeRoute.get(i + 1));
            Iterator<String> uncaughtExceptionIterator = uncaughtExceptions.iterator();
            while (uncaughtExceptionIterator.hasNext()) {
                String exception = uncaughtExceptionIterator.next();
                if (exception == null) break;
                if (exceptionCharacteristicManager.isCaught(edge, exception)) {
                    uncaughtExceptionIterator.remove();
                }
            }
        }
        return uncaughtExceptions;
    }
}
