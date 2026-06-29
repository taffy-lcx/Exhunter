package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;


 


public class RepoHandler {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static String REPOSITORY_INDEX_PATH;
    public static String REPOSITORY_DATA_PATH;
    private static String USER_NAME;
    private static String USER_TOKEN;
    private static List<Map<String, Object>> repositoryIndex = null;
    private static Map<String, String> cloneUrlMap = new HashMap<>();

    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            USER_NAME = prop.getProperty("GITHUB_USERNAME");
            USER_TOKEN = prop.getProperty("GITHUB_TOKEN");
            REPOSITORY_INDEX_PATH = prop.getProperty("REPOSITORY_INDEX_FILE");
            REPOSITORY_DATA_PATH = prop.getProperty("REPOSITORY_DATA_PATH");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
        
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(REPOSITORY_INDEX_PATH)) {
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            repositoryIndex = gson.fromJson(reader, type);
            LOGGER.info("loaded repository index from: " + REPOSITORY_INDEX_PATH);
        } catch (IOException e) {
            LOGGER.severe("repository index load error: " + e.getMessage());
            throw new RuntimeException(e);
        }
        for (Map<String, Object> repo: repositoryIndex) {
            cloneUrlMap.put((String) repo.get("full_name"), (String) repo.get("clone_url"));
        }
//        System.setProperty("http.proxyHost", "127.0.0.1");
//        System.setProperty("http.prixyPort", "7890");
//        System.setProperty("https.proxyHost", "127.0.0.1");
//        System.setProperty("https.prixyPort", "7890");
    }

     




    public static String getRepository(String name) {
        LOGGER.info("getting repository: " + name);
        
        if (!cloneUrlMap.containsKey(name)) {
            LOGGER.warning("index does not contain repository: " + name);
            return null;
        }
        String folderName = name.replaceAll("/", "_");
        String localPath = REPOSITORY_DATA_PATH + File.separator + folderName;
        
        if (checkFolderExists(REPOSITORY_DATA_PATH, folderName)) {
            LOGGER.info("local repository found: " + localPath);
            return localPath;
        }
        
        LOGGER.info("local repository for " + name + " not found, start clone from " + cloneUrlMap.get(name));
        try {
            
//            CloneProgressModitor progressModitor = new CloneProgressModitor();
            Git git = Git.cloneRepository()
                    .setURI(cloneUrlMap.get(name)) 
                    .setDirectory(new File(localPath)) 
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(USER_NAME, USER_TOKEN)) 
//                    .setProgressMonitor(progressModitor)
                    .call(); 
            LOGGER.info("Repository cloned successfully to: " + localPath);
            
            git.close();
            return localPath;
        } catch (GitAPIException e) {
            LOGGER.warning("Error cloning repository " + name + " : " + e.getMessage());
            File trash = new File(localPath);
            if (trash.exists()) {
                ProjectHandler.deleteDir(trash);
            }
        }
        return null;
    }

     






    public static boolean checkFolderExists(String directoryPath, String folderName) {
        File directory = new File(directoryPath);
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && file.getName().equals(folderName)) {
                        return true;
                    }
                }
            }
        } else {
            LOGGER.warning("path '" + directoryPath + "' does not exist or not a directory");
        }
        return false;
    }

    public static String getCommitHashFromPatch(String patch) throws IllegalArgumentException {
        if (patch.contains("/commit/")) {
            return patch.split("/commit/")[1].substring(0, 40);
        }
        throw new IllegalArgumentException(patch + " parse commitHash error");
    }

}

class CloneProgressModitor implements ProgressMonitor {

    private static final Logger LOGGER = Logger.getLogger("MAIN");
    @Override
    public void start(int totalTasks) {
        LOGGER.fine("start clone, total tasks: " + totalTasks);
    }

    @Override
    public void beginTask(String title, int totalWork) {
        LOGGER.fine("start task: " + title + ", total work: " + totalWork);
    }

    @Override
    public void update(int completed) {
        System.out.println("processed: " + completed);
    }

    @Override
    public void endTask() {
        System.out.println("task complete.");
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void showDuration(boolean b) {

    }
}

