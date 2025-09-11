package com.infinitecampus.ccs.lingo.utility;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
//import java.util.Base64;
//import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;

import com.infinitecampus.ccs.lingo.settings.Configuration;
//import com.infinitecampus.prism.Prism;


//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

public class FileUtilityHelper {
   //rivate static final Logger logger = LogManager.getLogger(FileUtilityHelper.class);
        // Initialize logger as static final
    private static final LogHelper logger;
    
    // Static initialization block
    static {
        try {
            logger = new LogHelper(Configuration.getInstance()).createLogger(FileUtilityHelper.class);
        } catch (Exception e) {
            // If logger initialization fails, throw runtime exception
            throw new ExceptionInInitializerError("Failed to initialize logger: " + e.getMessage());
        }
    }

   public static boolean deleteFileIfExists(String filePath) {
      File file = new File(filePath);
      if (file.exists()) {
         boolean deleted = file.delete();
         if (deleted) {
            //System.out.println("File deleted successfully: " + filePath);
            return true;
         } else {
            //System.out.println("Failed to delete the file: " + filePath);
            return false;
         }
      } else {
         //System.out.println("File does not exist: " + filePath);
         return true;
      }
   }

   public static void createFolderIfNotExists(String folderPath) {
      File folder = new File(folderPath);
      if (!folder.exists()) {
         if (folder.mkdirs()) {
            System.out.println("Folder created successfully: " + folderPath);
         } else {
            System.out.println("Failed to create the folder: " + folderPath);
         }
      } else {
         System.out.println("Folder already exists: " + folderPath);
      }

   }

   public static void RenameFile(String directoryName, String oldFileName, String newFileName) 
   throws IOException
   {
      Path oldFile = Paths.get(directoryName, oldFileName);
      Path newFile = Paths.get(directoryName, newFileName);
      int maxRetries = 3; // Number of retry attempts
      int attempt = 0;
      boolean success = false;

      //try {
         while (attempt < maxRetries && !success) {
            try {
               attempt++;
               //delete file iff already exists.
               deleteFileIfExists(newFileName);          
               // Attempt to rename (move) the file
                Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
                success = true;
            }catch (IOException e) {
               logger.logWarn("Attempt " + attempt + " to rename file failed: " + e.getMessage());
               if (attempt == maxRetries) {
                  throw new IOException("Failed to rename file after " + maxRetries + " attempts", e);
               }
               // Wait briefly before retrying to give the system time to recover
               try {
                  Thread.sleep(1000L);
               } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  throw new IOException("Rename operation interrupted during retries", ie);
               }
            }
         }

      //} catch (IOException e) {
        // throw new IOException("An error occurred during renaming", e);      
         
     // }
  }
   public static boolean fileExists(String filePath) {
      File file = new File(filePath);
      return file.exists();
   }

   public static String getFileName(String filePath) {
      try {
         File file = new File(filePath);
         return file.getName();
      } catch (Exception var2) {
         System.out.println("Error:" + var2.getMessage());
         return null;
      }
   }

   public static void WriteFilesOut(String path, byte[] FileData) {
      try {
         Files.write(Paths.get(path), FileData, new OpenOption[0]);
      } catch (IOException var3) {
         System.out.println("Error:" + var3.getMessage());
      }

   }
   public static void deleteFolderContents(Path directory) throws IOException {
      if (!Files.exists(directory)) {
         logger.logWarn("Warning: Directory " + directory + " does not exist.");
          return;
      }
      Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
              Files.delete(file); // Delete file
              return FileVisitResult.CONTINUE;
          }
  
          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
              if (exc == null) {
                  Files.delete(dir); // Delete directory after contents are deleted
                  return FileVisitResult.CONTINUE;
              } else {
                  throw exc; // Propagate exception
              }
          }
      });
  }
  
   public static String getDownloadedFileName(String downloadDir, long timeoutInSeconds) throws InterruptedException {
      File dir = new File(downloadDir);
      List<String> existingFiles = getFilesInDirectory(dir);
      long waitedTime = 0L;

      while(waitedTime < timeoutInSeconds * 1000L) {
         Thread.sleep(1000L);
         waitedTime += 1000L;
         List<String> currentFiles = getFilesInDirectory(dir);
         /*original need to test
         List<String> newFiles = (List)currentFiles.stream().filter((fileName) -> {
            return !existingFiles.contains(fileName);
         }).collect(Collectors.toList());
         */
         List<String> newFiles = currentFiles.stream()
                                   .filter(fileName -> !existingFiles.contains(fileName))
                                   .collect(Collectors.toList());

        // Check if there are any new files
        if (!newFiles.isEmpty()) {
         String downloadedFile = newFiles.get(0);

         // Check if the downloaded file is stable (i.e., its size isn't changing)
         File downloadedFileObj = new File(dir, downloadedFile);
         long previousSize = downloadedFileObj.length();
         Thread.sleep(2000L); // Wait for a 2 seconds to check for file size change
         long currentSize = downloadedFileObj.length();

         // If the file size is stable, consider the file as fully downloaded
         if (previousSize == currentSize) {
             return downloadedFile;
         }
     }
   }
// If no file is found within the timeout, return null
      return null;
   }
   private static List<String> getFilesInDirectory(File dir) {
       return Arrays.stream(dir.listFiles())
                    .filter(File::isFile)          // Filter out directories
                    .map(File::getName)            // Map each file to its name
                    .collect(Collectors.toList()); // Collect results as a List<String>
   }
   public static String getFileDir(String path, String appName, String module, String personID) {
      String testDir = path + File.separator + "documentFileVault"
                             + File.separator + appName
                             + File.separator + module
                             + File.separator + personID;
  
      File documentFileDirectory = new File(testDir);
  
      if (!documentFileDirectory.exists()) {
          boolean created = documentFileDirectory.mkdirs();
          if (!created) {
              throw new RuntimeException("Failed to create directory: " + testDir);
          }
      }
  
      return testDir + File.separator;
  }

  public static void copyPdfFiles(String Source,String Destination) throws IOException {
      Path sourcePath = Paths.get(Source);
      Path destinationPath = Paths.get(Destination);
      try {
          Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
          throw new IOException("Error copying file: " + e.getMessage(), e);
      }
   }
    public static byte[] getPDFBytes(String filelocation) {
            try (InputStream stream = new FileInputStream(filelocation);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = stream.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    return baos.toByteArray();
            } catch (IOException e) {
                e.printStackTrace();
                return null;                
            }
        }
/* original need to test
   private static List<String> getFilesInDirectory(File dir) {
      return (List)Arrays.stream(dir.listFiles()).filter(File::isFile).map(File::getName).collect(Collectors.toList());
   }
   */
}
