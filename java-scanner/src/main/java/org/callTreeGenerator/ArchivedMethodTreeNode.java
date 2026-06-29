package org.callTreeGenerator;

import org.jdkAnalyzer.SQLUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArchivedMethodTreeNode extends MethodTreeNode{
    private String trueName;
    
    private Map<String, String> exceptions;
    private String javadoc = null;

    public ArchivedMethodTreeNode(MethodTreeNode methodTreeNode) throws SQLException {
        this.node = methodTreeNode.getNode();
        this.binding = methodTreeNode.getBinding();
        generateTrueName();
        generateExceptions();
    }
    private void generateTrueName() {
        trueName = SQLUtil.getMethodNameIfExist(this.getQualifiedName());
    }
    private void generateExceptions() {
        Map<String, String> exceptions = new HashMap<>();
        try (ResultSet rs = SQLUtil.selectExceptionThrownByMethod(trueName)) {
            while (rs.next()) {
                exceptions.put(rs.getString("exception"), "");
            }
            exceptions.putAll(getDocExceptions());
        } catch (SQLException e) {
            // ignore
        } finally {
            this.exceptions = exceptions;
        }
    }
    private Map<String, String> getDocExceptions(){
        if (javadoc == null) {
            initJavaDoc();
        }
        Map<String, String> exceptions = new LinkedHashMap<>(); 
        try {
            
            String regex = "@throws\\s+(\\S+)\\s+(.*)";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(javadoc);

            while (matcher.find()) {
                String exceptionType = matcher.group(1);  
                String exceptionCondition = matcher.group(2);  
                exceptions.put(exceptionType, exceptionCondition);
            }
        } catch (NullPointerException e) {
            // ignore
        }
        return exceptions;
    }
    private void initJavaDoc(){
        javadoc = "";
        try (ResultSet rs = SQLUtil.executeSelect("SELECT comment FROM methods WHERE qualified_name = '" + trueName + "'");) {
            if (rs != null && rs.next()) {
                javadoc = rs.getString("comment");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void makeExceptionNameQualified(ExceptionCharacteristicManager exceptionCharacteristicManager) {
        Map<String, String> newExceptions = new HashMap<>();
        for (Map.Entry<String, String> entry: this.exceptions.entrySet()) {
            newExceptions.put(exceptionCharacteristicManager.getQualifiedName(entry.getKey()), entry.getValue());
        }
        this.exceptions = newExceptions;
    }

    public String getTrueName() {
        return trueName;
    }

    public Map<String, String> getExceptions() {
        return exceptions;
    }

    public String getJavaDoc() {
        if (javadoc == null) {
            initJavaDoc();
        }
        return javadoc;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getQualifiedName());
        sb.append("\n");
        sb.append(exceptions.keySet().toString());
        return sb.toString();
    }
}
