package org.jdkAnalyzer;

import org.eclipse.jdt.core.dom.CompilationUnit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class Main {

    public static String jdkAbsPath;
    public static List<String> ignore = new ArrayList<>(Arrays.asList(
            "test\\langtools\\tools\\javac\\4917091",
            "test\\langtools\\tools\\javac\\limits",
            "test\\hotspot\\jtreg\\runtime\\LoadClass\\dummy.jar",
            "bin\\test\\hotspot\\jtreg\\runtime\\LoadClass\\dummy.jar"
    ));

    public static void main(String[] args) {
        jdkAbsPath = args.length > 0 ? args[0] : System.getenv("JDK_SOURCE_PATH");
        if (jdkAbsPath == null || jdkAbsPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Provide the JDK source path as the first argument or JDK_SOURCE_PATH");
        }
        mainContent();
    }

    private static void mainContent(){
        System.out.println("start parse at " + new Date());
        ProjectParser pjpsr = new ProjectParser(jdkAbsPath, ignore);
        List<String> javaFiles = pjpsr.getJavaFiles();
        for(String filePath : javaFiles){
            if (SQLUtil.isFileVisited(filePath.substring(jdkAbsPath.length() + 1))){
                System.out.println("already scanned " + filePath + ", skip.");
                continue;
            }
            System.out.println("start compile " + filePath + " at " + new Date());
            CompilationUnit cu;
            try {
                cu = pjpsr.getCompilationUnitWithBindings(
                        filePath
                );
            } catch (NullPointerException ignore){
                System.err.println("failed compile file due to NullPointerException.");
                SQLUtil.insertFile(filePath.substring(jdkAbsPath.length() + 1));
                continue;
            }
            System.out.println("start visit " + filePath + " at " + new Date());
            cu.accept(new ThreeInOneVisitor(filePath));
            SQLUtil.insertFile(filePath.substring(jdkAbsPath.length() + 1));
        }
    }

    private static void test() {
        String sqlQuery = "SELECT ms.qualified_name, cs.method_been_call, ts.exception, ts.throw_way, ts.throw_condition FROM methods ms JOIN calls cs ON cs.method = ms.qualified_name JOIN throws ts ON ts.method = cs.method_been_call WHERE ms.qualified_name = 'java.awt.SplashScreen.update()';";
        try (ResultSet rs = SQLUtil.executeSelect(sqlQuery)){
            assert rs != null;
            System.out.println(rs.toString());
            while (rs.next()) {
                
                String resultData = rs.getString("qualified_name");
                System.out.println("Result: " + resultData);
            }
        } catch (SQLException ignore){
            // ignore
        }
    }
}
