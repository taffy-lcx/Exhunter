package org.jdkAnalyzer;

import org.eclipse.jdt.core.dom.*;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;

public class ProjectParser {

    private static ASTParser astParser;
    private final String projectRoot;
    private final List<String> pathsIgnore;
    private final List<String> javaFiles;
    private final List<String> classEntriesList;
    private String[] sourcePathEntries;
    private String[] classpathEntries;
    private String[] encodings;
    private String sourceCode;
    private Map<String, String> fileSourceMap = new HashMap<>();

    public ProjectParser(String projectRoot, List<String> pathsIgnore){
        astParser = ASTParser.newParser(AST.JLS21);
        astParser.setKind(ASTParser.K_COMPILATION_UNIT);
        this.projectRoot = projectRoot;
        this.pathsIgnore = pathsIgnore;
        this.javaFiles = listJavaFiles(projectRoot);
        this.classEntriesList = listJarFiles(this.projectRoot);
        parseSourceEntries();

    }
    public ProjectParser(String projectRoot){
        this(projectRoot, new ArrayList<String>());
    }
    private void parseSourceEntries(){
        HashSet<String> sourceRootSet = new HashSet<>();
        for (String javaFile : this.javaFiles) {
            CompilationUnit compilationUnit = getCompilationUnit(javaFile);
            if (compilationUnit.getPackage() == null) continue;
            try {
                String rootPath = parseRootPath(javaFile, compilationUnit.getPackage().getName().toString());
                if (!rootPath.isEmpty() && Paths.get(rootPath).toFile().exists()){
                    sourceRootSet.add(rootPath);
                    this.fileSourceMap.put(javaFile, rootPath);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        String[] sourcePathEntries = new String[sourceRootSet.size()];
        String[] encodings = new String[sourceRootSet.size()];
        int index = 0;
        for (String sourceRoot : sourceRootSet) {
            sourcePathEntries[index] = sourceRoot;
            encodings[index] = "utf-8";
            index++;
        }
        this.sourcePathEntries = sourcePathEntries;
        this.encodings = encodings;
        if (this.classEntriesList.isEmpty()){
            classpathEntries = null;
        }
        else {
            String[] classpathEntries = new String[this.classEntriesList.size()];
            index = 0;
            for (String classpath : this.classEntriesList){
                classpathEntries[index] = classpath;
                index++;
            }
            this.classpathEntries = classpathEntries;
        }
    }

    private String parseRootPath(String filePath, String packageName) {
        String path = packageName.replaceAll("\\.", Matcher.quoteReplacement(File.separator));
        Path relativePath = Paths.get(path);
        Path absolutePath = Paths.get(filePath).resolveSibling("");
        int end = absolutePath.toString().lastIndexOf(relativePath.toString());
        if (end == -1) return "";
        return absolutePath.toString().substring(0, end).replace("\\", "/");
    }

    public static CompilationUnit getCompilationUnit(String javaFilePath){
        byte[] input = null;
        try {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(javaFilePath));
            input = new byte[bufferedInputStream.available()];
            bufferedInputStream.read(input);
            bufferedInputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        astParser.setSource(new String(input).toCharArray());

        return (CompilationUnit) (astParser.createAST(null));
    }

    public CompilationUnit getCompilationUnitWithBindings(String javaFilePath){
        byte[] input = null;
        try {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(javaFilePath));
            input = new byte[bufferedInputStream.available()];
            bufferedInputStream.read(input);
            bufferedInputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.sourceCode = new String(input);
        astParser.setSource(sourceCode.toCharArray());

        astParser.setKind(ASTParser.K_COMPILATION_UNIT);
        astParser.setEnvironment(classpathEntries, sourcePathEntries, encodings, true);
        astParser.setResolveBindings(true);
        astParser.setBindingsRecovery(true);
        astParser.setUnitName("");

        return (CompilationUnit) (astParser.createAST(null));
    }

    public List<String> listJavaFiles(String root){
        List<String> files = new ArrayList<>();
        if (isIgnored(new File(root).getAbsolutePath())) return files;
        if (new File(root).isDirectory()){
            for (File f : new File(root).listFiles()) {
                files.addAll(listJavaFiles(f.getAbsolutePath()));
            }
        }
        else {
            if (root.endsWith(".java")) {
                files.add(new File(root).getAbsolutePath());
            }
        }
        return files;
    }

    private List<String> listJarFiles(String root){
        List<String> files = new ArrayList<>();
        if (isIgnored(new File(root).getAbsolutePath())) return files;
        if (new File(root).isDirectory()){
            for (File f : new File(root).listFiles()) {
                files.addAll(listJarFiles(f.getAbsolutePath()));
            }
        }
        else {
            if (root.endsWith(".jar")) {
                files.add(new File(root).getAbsolutePath());
            }
        }
        return files;
    }

    private Boolean isIgnored(String path){
        for (String s : this.pathsIgnore){
            if (Objects.equals(projectRoot + "\\" + s, path)){
                System.out.println("ignored " + path);
                return true;
            }
        }
        return false;
    }

    public List<String> getJavaFilesUnderSourcePath() {
        List<String> javaFilesUnderSourcePath = new ArrayList<>();
        for (String j : this.javaFiles){
            boolean inFlag = false;
            for (String p : this.sourcePathEntries){
                if (j.startsWith(p)) {
                    inFlag = true;
                    break;
                }
            }
            if (inFlag) javaFilesUnderSourcePath.add(j);
        }
        return javaFilesUnderSourcePath;
    }

    public List<String> getJavaFiles() {
        return this.javaFiles;
    }

    public String getClassName(String path){
        if (this.fileSourceMap.containsKey(path)){
            String sourcePath = this.fileSourceMap.get(path);
            String shortPath = path.substring(sourcePath.length(), path.length() - 5);
            return shortPath.replace(File.separatorChar, '.');
        }
        else {
            return null;
        }
    }

    public String IMethodBinding2fileName(IMethodBinding methodBinding) {
        return ITypeBinding2fileName(methodBinding.getDeclaringClass());
    }

    public String ITypeBinding2fileName(ITypeBinding typeBinding) {
        while (typeBinding.isNested()) {
            typeBinding = typeBinding.getDeclaringClass();
        }
        String path = typeBinding.getQualifiedName().replace('.', File.separatorChar) + ".java";
        for (String absPath: javaFiles) {
            if (absPath.endsWith(path)) {
                return absPath;
            }
        }
        return null;
    }
}
