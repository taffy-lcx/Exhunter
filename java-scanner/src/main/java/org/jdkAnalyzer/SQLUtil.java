package org.jdkAnalyzer;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static java.sql.DriverManager.getConnection;

public class SQLUtil {
    static final String databaseName = "analyzeData1.db";
    static final Connection conn;
    static PreparedStatement insertCLassAndModifierAndTypeStatement;
    static PreparedStatement updateClassCommentStatement;
    static PreparedStatement insertExceptionAndModifierAndParentAndTypeStatement;
    static PreparedStatement updateExceptionCommentStatement;
    static PreparedStatement insertInheritStatement;
    static PreparedStatement insertMethodAndModifierAndContentStatement;
    static PreparedStatement updateMethodCommentStatement;
    static PreparedStatement insertThrowStatement;
    static PreparedStatement insertThrowWithConditionStatement;
    static PreparedStatement insertBelongStatement;
    static PreparedStatement insertCallStatement;
    static PreparedStatement selectClassStatement;
    static PreparedStatement insertFileStatement;
    static PreparedStatement insertFileClassStatement;
    static PreparedStatement selectFilesStatement;
    static PreparedStatement selectParentClassStatement;
    static PreparedStatement selectExceptionSimpleNameStatement;
    static PreparedStatement selectMethodExactNameStatement;
    static PreparedStatement selectMethodFuzzyNameStatement;
    static PreparedStatement selectExceptionThrownByMethodStatement;

    static {
        try {
            String url = "jdbc:sqlite:" + databaseName;
            conn = getConnection(url);
            insertCLassAndModifierAndTypeStatement = conn.prepareStatement("INSERT OR IGNORE INTO classes (qualified_name, modifier, class_type) VALUES (?, ?, ?)");
            updateClassCommentStatement = conn.prepareStatement("UPDATE classes SET comment = ? WHERE qualified_name = ?");
            insertExceptionAndModifierAndParentAndTypeStatement = conn.prepareStatement("INSERT OR IGNORE INTO exceptions (qualified_name, modifier, parent, exception_type) VALUES (?, ?, ?, ?)");
            updateExceptionCommentStatement = conn.prepareStatement("UPDATE exceptions SET comment = ? WHERE qualified_name = ?");
            insertInheritStatement = conn.prepareStatement("INSERT OR IGNORE INTO inherits (class, parent, relation) VALUES (?, ?, ?)");
            insertMethodAndModifierAndContentStatement = conn.prepareStatement("INSERT OR IGNORE INTO methods (qualified_name, modifier, content) VALUES (?, ?, ?)");
            updateMethodCommentStatement = conn.prepareStatement("UPDATE methods SET comment = ? WHERE qualified_name = ?");
            insertThrowStatement = conn.prepareStatement("INSERT OR IGNORE INTO throws (throw_key, method, exception, throw_way) VALUES (?, ?, ?, ?)");
            insertThrowWithConditionStatement = conn.prepareStatement("INSERT OR IGNORE INTO throws (throw_key, method, exception, throw_way, throw_condition) VALUES (?, ?, ?, ?, ?)");
            insertBelongStatement = conn.prepareStatement("INSERT OR IGNORE INTO belongs (method, class) VALUES (?, ?)");
            insertCallStatement = conn.prepareStatement("INSERT OR IGNORE INTO calls (method, method_been_call) VALUES (?, ?)");
            selectClassStatement = conn.prepareStatement("SELECT * FROM classes Where qualified_name = ?");
            insertFileStatement = conn.prepareStatement("INSERT OR IGNORE INTO files (file_path) VALUES (?)");
            insertFileClassStatement = conn.prepareStatement("INSERT OR IGNORE INTO locates (file_path, class) VALUES (?, ?)");
            selectFilesStatement = conn.prepareStatement("SELECT * FROM files Where file_path = ?");
            selectParentClassStatement = conn.prepareStatement("SELECT parent FROM inherits WHERE class = ? AND relation = 'Inheritance'");
            selectExceptionSimpleNameStatement = conn.prepareStatement("SELECT qualified_name FROM exceptions WHERE qualified_name LIKE ?");
            selectMethodExactNameStatement = conn.prepareStatement("SELECT qualified_name FROM methods WHERE qualified_name = ?");
            selectMethodFuzzyNameStatement = conn.prepareStatement("SELECT qualified_name FROM methods WHERE qualified_name LIKE ?");
            selectExceptionThrownByMethodStatement = conn.prepareStatement("SELECT * FROM throws WHERE method = ?");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int insertCLassAndModifierAndType(String className, int modifier, String type){
        try {
            insertCLassAndModifierAndTypeStatement.setString(1, className);
            insertCLassAndModifierAndTypeStatement.setInt(2, modifier);
            insertCLassAndModifierAndTypeStatement.setString(3, type);
            return insertCLassAndModifierAndTypeStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int updateClassComment(String className, String comment){
        try{
            updateClassCommentStatement.setString(1, comment);
            updateClassCommentStatement.setString(2, className);
            return updateClassCommentStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertExceptionAndModifierAndParentAndType(String exceptionName, int modifier, String parent, String type){
        try{
            insertExceptionAndModifierAndParentAndTypeStatement.setString(1, exceptionName);
            insertExceptionAndModifierAndParentAndTypeStatement.setInt(2, modifier);
            insertExceptionAndModifierAndParentAndTypeStatement.setString(3, parent);
            insertExceptionAndModifierAndParentAndTypeStatement.setString(4, type);
            return insertExceptionAndModifierAndParentAndTypeStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int updateExceptionComment(String exceptionName, String comment){
        try {
            updateExceptionCommentStatement.setString(1, comment);
            updateExceptionCommentStatement.setString(2, exceptionName);
            return updateExceptionCommentStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertInherit(String class_name, String parent_name, String relation){
        try {
            insertInheritStatement.setString(1, class_name);
            insertInheritStatement.setString(2, parent_name);
            insertInheritStatement.setString(3, relation);
            return insertInheritStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertMethodAndModifierAndContent(String name, int modifier, String content){
        try{
            insertMethodAndModifierAndContentStatement.setString(1, name);
            insertMethodAndModifierAndContentStatement.setInt(2, modifier);
            insertMethodAndModifierAndContentStatement.setString(3, content);
            return insertMethodAndModifierAndContentStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int updateMethodComment(String methodName, String comment){
        try {
            updateMethodCommentStatement.setString(1, comment);
            updateMethodCommentStatement.setString(2, methodName);
            return updateMethodCommentStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertThrowMethodAndExceptionAndWay(String key, String method, String exception, String way){
        try {
            insertThrowStatement.setString(1, key);
            insertThrowStatement.setString(2, method);
            insertThrowStatement.setString(3, exception);
            insertThrowStatement.setString(4, way);
            return insertThrowStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertThrowMethodAndExceptionAndWayAndCondition(String key, String method, String exception, String way, String condition){
        try {
            insertThrowWithConditionStatement.setString(1, key);
            insertThrowWithConditionStatement.setString(2, method);
            insertThrowWithConditionStatement.setString(3, exception);
            insertThrowWithConditionStatement.setString(4, way);
            insertThrowWithConditionStatement.setString(5, condition);
            return insertThrowWithConditionStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertBelong(String class_name, String methodName){
        try {
            insertBelongStatement.setString(1, class_name);
            insertBelongStatement.setString(2, methodName);
            return insertBelongStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertCall(String method, String methodCalled){
        try {
            insertCallStatement.setString(1, method);
            insertCallStatement.setString(2, methodCalled);
            return insertCallStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static ResultSet selectClass(String qualified_name){
        try {
            selectClassStatement.setString(1, qualified_name);
            return selectClassStatement.executeQuery();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static boolean isClassExist(String qualified_name){
        if (qualified_name == null){
            return false;
        }
        try (ResultSet rs = selectClass(qualified_name)) {
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int insertFile(String file_path){
        try {
            insertFileStatement.setString(1, file_path);
            return insertFileStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int insertFileClass(String file_path, String qualifiedName){
        try {
            insertFileClassStatement.setString(1, file_path);
            insertFileClassStatement.setString(2, qualifiedName);
            return insertFileClassStatement.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static ResultSet selectFile(String filePath){
        try {
            selectFilesStatement.setString(1, filePath);
            return selectFilesStatement.executeQuery();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static boolean isFileVisited(String filePath){
        if (filePath == null){
            return false;
        }
        try (ResultSet rs = selectFile(filePath)) {
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getParentClassName(String qualifiedName) {
        String name = null;
        try (ResultSet rs = selectParentClass(qualifiedName)) {
            while (rs.next()) {
                if (name != null) {
                    throw new IllegalStateException("shouldn't have multiple parent classes!");
                }
                name = rs.getString("parent");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return name;
    }

    public static ResultSet selectParentClass(String qualifiedName) {
        try {
            selectParentClassStatement.setString(1, qualifiedName);
            return selectParentClassStatement.executeQuery();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

     



    public static String getFullExceptionName(String simpleName) {
        
        try (ResultSet rs = selectExceptionSimpleName(simpleName)) {
            while (rs.next()) {
                return rs.getString("qualified_name");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public static ResultSet selectExceptionSimpleName(String simpleName) {
        String pattern = "%" + simpleName;
        try {
            selectExceptionSimpleNameStatement.setString(1, pattern);
            return selectExceptionSimpleNameStatement.executeQuery();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static int isSimpleExceptionRuntime(String simpleName) {
        String className = getFullExceptionName(simpleName);
        if (className == null) {
            return -1;
        }
        List<String> inheritances = new ArrayList<>();
        while (className != null) {
            inheritances.add(className);
            className = SQLUtil.getParentClassName(className);
        }
        if (inheritances.contains("java.lang.RuntimeException")) {
            return 1;
        } else {
            return 0;
        }
    }

     



    public static String getMethodNameIfExist(String qualifiedName) {
        try {
            selectMethodExactNameStatement.setString(1, qualifiedName);
            try (ResultSet rs = selectMethodExactNameStatement.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("qualified_name");
                }
            }
            List<String> methodGenericsParameterNames = generateMethodGenericsParameterNames(qualifiedName);
            for (String name: methodGenericsParameterNames) {
                selectMethodFuzzyNameStatement.setString(1, name);
                try (ResultSet rs = selectMethodFuzzyNameStatement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("qualified_name");
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> generateMethodGenericsParameterNames(String qualifiedName) {
        List<Integer> endParenthesisIndexes = findIndexes(qualifiedName, ')');
        List<Integer> startParenthesisIndexes = findIndexes(qualifiedName, '(');
        if (endParenthesisIndexes.size() != 1 || startParenthesisIndexes.size() != 1) {
            return new ArrayList<>();
        } else if (endParenthesisIndexes.get(0) - startParenthesisIndexes.get(0) == 1) {
            return new ArrayList<>();
        }
        List<Integer> targetIndexes = findIndexes(qualifiedName, ',');
        targetIndexes.add(endParenthesisIndexes.get(0));
        List<String> results = new ArrayList<>();
        for (int i = 1; i < Math.pow(2, targetIndexes.size()); i++) {
            String binaryString = String.format("%" +String.valueOf(targetIndexes.size()) +  "s", Integer.toBinaryString(i)).replace(' ', '0');
            StringBuilder newQualifiedName = new StringBuilder(qualifiedName);
            for (int j = targetIndexes.size() - 1; j >= 0; j--) {
                if (binaryString.charAt(j) == '1') {
                    newQualifiedName.insert(targetIndexes.get(j), "<*>");
                }
            }
            results.add(newQualifiedName.toString());
        }
        return results;
    }
    private static List<Integer> findIndexes(String input, char targetChar) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == targetChar) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    public static ResultSet selectExceptionThrownByMethod(String methodName) {
        try {
            selectExceptionThrownByMethodStatement.setString(1, methodName);
            return selectExceptionThrownByMethodStatement.executeQuery();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public static ResultSet executeSelect(String query){
        Statement statement;
        ResultSet resultSet;
        try {
            statement = conn.createStatement();
            resultSet = statement.executeQuery(query);
            return resultSet;
        } catch (SQLException ignore){
            // ignore
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        String method_name_pattern = "java.util.Collections.indexedBinarySearch(List<%>,T)";
        String sql = "SELECT qualified_name FROM methods WHERE qualified_name LIKE '" + method_name_pattern + "'";
        System.out.println(sql);
        try (ResultSet rs = executeSelect(sql)) {
            if (rs != null) {
                while (rs.next()) {
                    System.out.println(rs.getString("qualified_name"));
                }
            } else {
                System.out.println("null ResultSet");
            }
        }
    }
}
