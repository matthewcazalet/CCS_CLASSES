package com.infinitecampus.ccs.formstapler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Logger;

/**
 * A utility class for common file and directory operations.
 * This class uses the modern java.nio.file API for consistency and robustness.
 */
public final class FileUtilityHelper {

    private static final Logger logger = AppLogger.getLogger(FileUtilityHelper.class);

    // Private constructor to prevent instantiation of this utility class.
    private FileUtilityHelper() {}

    /**
     * Deletes a file if it exists. Does not throw an error if the file is not found.
     * @param filePath The path to the file to delete.
     * @return true if the file was deleted or did not exist, false if deletion failed.
     */
    public static boolean deleteFileIfExists(String filePath) {
        try {
            return Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            logger.error("Failed to delete file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Creates a directory including any necessary but nonexistent parent directories.
     * If the directory already exists, this method does nothing.
     * @param folderPath The path of the folder to create.
     */
    public static void createDirectories(String folderPath) throws IOException {
        try {
            Files.createDirectories(Paths.get(folderPath));
        } catch (IOException e) {
            logger.error("Failed to create directory: {}", folderPath, e);
            throw e; // Re-throw so the caller knows the operation failed.
        }
    }

    /**
     * Renames (moves) a file with a retry mechanism for robustness.
     * @param directory The directory containing the file.
     * @param oldFileName The current name of the file.
     * @param newFileName The new name for the file.
     */
    public static void renameFileWithRetry(String directory, String oldFileName, String newFileName) throws IOException {
        Path oldFile = Paths.get(directory, oldFileName);
        Path newFile = Paths.get(directory, newFileName);
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                attempt++;
                Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
                logger.debug("Successfully renamed {} to {}", oldFile, newFile);
                return; // Success, exit the method.
            } catch (IOException e) {
                logger.warn("Attempt {} to rename file failed: {}", attempt, e.getMessage());
                if (attempt == maxRetries) {
                    throw new IOException("Failed to rename file after " + maxRetries + " attempts", e);
                }
                try {
                    Thread.sleep(1000); // Wait 1 second before retrying.
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Rename operation interrupted during retries", ie);
                }
            }
        }
    }

    /**
     * Checks if a file or directory exists at the given path.
     * @param filePath The path to check.
     * @return true if the path exists, false otherwise.
     */
    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * Reads all bytes from a file into a byte array.
     * @param filePath The path to the file.
     * @return A byte array containing the file's contents.
     */
    public static byte[] readFileToBytes(String filePath) throws IOException {
        try {
            return Files.readAllBytes(Paths.get(filePath));
        } catch (IOException e) {
            logger.error("Failed to read bytes from file: {}", filePath, e);
            throw e;
        }
    }

    /**
     * Writes a byte array to a file, creating the file if it doesn't exist and overwriting it if it does.
     * @param filePath The path of the file to write to.
     * @param fileData The byte array to write.
     */
    public static void writeBytesToFile(String filePath, byte[] fileData) throws IOException {
        try {
            Files.write(Paths.get(filePath), fileData);
        } catch (IOException e) {
            logger.error("Failed to write bytes to file: {}", filePath, e);
            throw e;
        }
    }

    /**
     * Deletes a directory and all its contents recursively.
     * If the directory does not exist, the method does nothing.
     * @param directory The path to the directory to delete.
     */
    public static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            logger.warn("Attempted to delete a non-existent directory: {}", directory);
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
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

    /**
     * Copies a file from a source to a destination, replacing the destination if it exists.
     * @param sourcePath The path to the source file.
     * @param destinationPath The path to the destination file.
     */
    public static void copyFile(String sourcePath, String destinationPath) throws IOException {
        try {
            Files.copy(Paths.get(sourcePath), Paths.get(destinationPath), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Error copying file from {} to {}", sourcePath, destinationPath, e);
            throw new IOException("Error copying file: " + e.getMessage(), e);
        }
    }
}