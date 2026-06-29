package org.ExperimentExecutor;

import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;


 


public class ProjectHandler {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static String TMP_PROJECT_PATH;
    private static ASTParser astParser;
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            TMP_PROJECT_PATH = prop.getProperty("TMP_PROJECT_PATH");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static CompilationUnit getMethodCompilationUnit(String methodContent) throws IllegalStateException{
        astParser.setSource(methodContent.toCharArray());
        return (CompilationUnit) (astParser.createAST(null));
    }

     





    static public List<String> generateCommitParentHistoryFiles(String repositoryPath, String commitHash) {
        LOGGER.info("Start generate parent history files of commit : " + commitHash);
        try{
            // Open your local repository using JGit
            // Get a repository
            Repository repository = new FileRepositoryBuilder()
                    .setGitDir(new File(repositoryPath, ".git"))
                    .build();
            // Create a RevWalk object
            RevWalk walk = new RevWalk(repository);
            // Get the commit
            ObjectId childCommitId = ObjectId.fromString(commitHash);
            RevCommit childCommit = walk.parseCommit(childCommitId);
            // get parent
            String parentHash;
            if (childCommit.getParentCount() > 0) {
                parentHash = childCommit.getParent(0).getName();
            } else {
                LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to no parent found.");
                return null;
            }
            LOGGER.fine("from commit " + commitHash + " get parent commit " + parentHash);
            ObjectId commitId = ObjectId.fromString(parentHash);
            RevCommit commit = walk.parseCommit(commitId);
            // Get the file tree of the commit
            TreeWalk treeWalk = new TreeWalk(repository);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            // start generate
            List<String> projectFiles = new ArrayList<>();
//            File folder = new File(TMP_PROJECT_PATH);
//            deleteDirContent(folder);
            cleanFolder(TMP_PROJECT_PATH);
            while (treeWalk.next()){
                // traverse the java files in the commit history
                if (treeWalk.getPathString().endsWith(".java") || treeWalk.getPathString().endsWith(".jar")){
                    // generate file in corresponding path
                    String fileLocation = TMP_PROJECT_PATH + "/" + treeWalk.getPathString();
                    projectFiles.add(fileLocation);
                    File file = new File(fileLocation);
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                    // write file
                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);
                    InputStream in = loader.openStream();
                    OutputStream out = new FileOutputStream(file);
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
            LOGGER.info("file generation complete for parent of commit : " + commitHash);
            return projectFiles;
        }
        catch (Exception e){
            LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to " + e.getMessage());
//            e.printStackTrace();
            return null;
        }
    }

     





    static public List<String> generateCommitParentHistoryFiles(String repositoryPath, String commitHash, String fileName) {
        LOGGER.info("Start generate parent history files of commit : " + commitHash);
        try{
            // Open your local repository using JGit
            // Get a repository
            Repository repository = new FileRepositoryBuilder()
                    .setGitDir(new File(repositoryPath, ".git"))
                    .build();
            // Create a RevWalk object
            RevWalk walk = new RevWalk(repository);
            // Get the commit
            ObjectId childCommitId = ObjectId.fromString(commitHash);
            RevCommit childCommit = walk.parseCommit(childCommitId);
            // get parent
            String parentHash;
            if (childCommit.getParentCount() > 0) {
                parentHash = childCommit.getParent(0).getName();
            } else {
                LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to no parent found.");
                return null;
            }
            LOGGER.fine("from commit " + commitHash + " get parent commit " + parentHash);
            ObjectId commitId = ObjectId.fromString(parentHash);
            RevCommit commit = walk.parseCommit(commitId);
            // Get the file tree of the commit
            TreeWalk treeWalk = new TreeWalk(repository);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            // start generate
            List<String> projectFiles = new ArrayList<>();
//            File folder = new File(TMP_PROJECT_PATH);
//            deleteDirContent(folder);
            recreateFolder(fileName);
            while (treeWalk.next()){
                // traverse the java files in the commit history
                if (treeWalk.getPathString().endsWith(".java") || treeWalk.getPathString().endsWith(".jar")){
                    // generate file in corresponding path
                    String fileLocation = TMP_PROJECT_PATH + "\\" + fileName + "/" + treeWalk.getPathString();
                    projectFiles.add(fileLocation);
                    File file = new File(fileLocation);
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                    // write file
                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);
                    InputStream in = loader.openStream();
                    OutputStream out = new FileOutputStream(file);
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
            LOGGER.info("file generation complete for parent of commit : " + commitHash);
            return projectFiles;
        }
        catch (Exception e){
            LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to " + e.getMessage());
//            e.printStackTrace();
            return null;
        }
    }

     




    public static void recreateFolder(String folderName) throws IOException {
        deleteFolder(folderName);
        
        String project_Path = getFullPathFromName(folderName);
        Path path = Paths.get(project_Path);
        Files.createDirectories(path);
    }

     




    public static void deleteFolder(String folderName) throws IOException {
        String project_Path = getFullPathFromName(folderName);
        Path path = Paths.get(project_Path);

        
        if (Files.exists(path)) {
            
            if (!Files.isDirectory(path)) {
                throw new IOException("Path is not a directory: " + project_Path);
            }

            
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static String getFullPathFromName(String name) {
        return TMP_PROJECT_PATH + "\\" + name;
    }

    public static void sleepSecond(int s) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        
        executor.schedule(() -> {
            LOGGER.info(String.format("wait %d s", s));
            
        }, s, TimeUnit.SECONDS);

        
        executor.shutdown();
    }

     



    public static void deleteDir(File dir) {
        if (!dir.exists()) return;

        try {
            Path path = toLongPath(dir); 
            Files.walk(path)
                    .sorted(Comparator.reverseOrder()) 
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to delete: " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete directory: " + dir, e);
        }
    }

     


    public static void deleteDirContent(File dir) {
        if (!dir.isDirectory()) return;

        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            try {
                Path childPath = toLongPath(child);
                if (Files.isSymbolicLink(childPath)) {
                    Files.delete(childPath); 
                } else {
                    deleteDir(child); 
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete: " + child, e);
            }
        }
    }

     



    private static Path toLongPath(File file) {
        String path = file.getAbsolutePath();

        
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (path.startsWith("\\\\")) {
                
                return Paths.get("\\\\?\\UNC\\" + path.substring(2));
            } else {
                
                return Paths.get("\\\\?\\" + path);
            }
        } else {
            
            return file.toPath();
        }
    }

     




    public static void cleanFolder(String folderPath) throws IOException {
        Path path = Paths.get(folderPath);

        
        if (!Files.exists(path)) {
            Files.createDirectories(path);
            LOGGER.info("created missing TMP_PROJECT_PATH: " + folderPath);
            return; 
        }

        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path is not a directory: " + folderPath);
        }

        
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.equals(path)) {  
                    Files.delete(file);    
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc; 
                }
                if (!dir.equals(path)) {  
                    Files.delete(dir);   
                }
                return FileVisitResult.CONTINUE;
            }
        });
        LOGGER.info("clean folder " + folderPath + " success");
    }
}
