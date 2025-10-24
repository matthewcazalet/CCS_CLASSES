package com.test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FindAllDependencies {
    public static void main(String[] args) throws Exception {
        // Path to the ccs_lingo JAR
        String jarPath = "../ccs_lingo/target/ccs_lingo-2025.06.jar";
        
        System.out.println("Analyzing dependencies in: " + jarPath);
        System.out.println("This might help identify what's needed...\n");
        
        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            System.err.println("JAR file not found: " + jarPath);
            return;
        }
        
        // Try to load CampusObject and see what it needs
        try {
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()});
            Class<?> campusObjectClass = classLoader.loadClass("com.infinitecampus.CampusObject");
            System.out.println("Successfully loaded CampusObject class");
        } catch (NoClassDefFoundError e) {
            System.err.println("Missing dependency: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        
        // Common dependencies that CampusObject might need
        String[] commonDependencies = {
            "org.quartz.SchedulerException",
            "com.icl.saxon.TransformerFactoryImpl",
            "javax.servlet.http.HttpServlet",
            "org.apache.log4j.Logger",
            "org.slf4j.Logger",
            "javax.mail.Message",
            "org.springframework.context.ApplicationContext",
            "org.hibernate.Session"
        };
        
        System.out.println("\nChecking common enterprise dependencies:");
        for (String className : commonDependencies) {
            try {
                Class.forName(className);
                System.out.println("✓ Found: " + className);
            } catch (ClassNotFoundException e) {
                System.out.println("✗ Missing: " + className);
            }
        }
    }
}