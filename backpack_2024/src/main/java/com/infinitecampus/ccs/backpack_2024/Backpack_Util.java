// Source code is decompiled from a .class file using FernFlower decompiler.
package com.infinitecampus.ccs.backpack_2024;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Backpack_Util {
   public Backpack_Util() {
   }

   public static void zip(File directory, File zipfile) throws IOException {
      URI base = directory.toURI();
      Deque<File> queue = new LinkedList();
      queue.push(directory);
      OutputStream out = new FileOutputStream(zipfile);
      Closeable res = out;

      try {
         ZipOutputStream zout = new ZipOutputStream(out);
         res = zout;

         while(!queue.isEmpty()) {
            directory = (File)queue.pop();
            File[] var10;
            int var9 = (var10 = directory.listFiles()).length;

            for(int var8 = 0; var8 < var9; ++var8) {
               File kid = var10[var8];
               String name = base.relativize(kid.toURI()).getPath();
               if (kid.isDirectory()) {
                  queue.push(kid);
                  name = name.endsWith("/") ? name : name + "/";
                  zout.putNextEntry(new ZipEntry(name));
               } else {
                  zout.putNextEntry(new ZipEntry(name));
                  copy((File)kid, (OutputStream)zout);
                  zout.closeEntry();
               }
            }
         }
      } finally {
         ((Closeable)res).close();
      }

   }

   public static void unzip(File zipfile, File directory) throws Throwable {
      Throwable var2 = null;
      Object var3 = null;

      try {
         ZipFile zfile = new ZipFile(zipfile);

         try {
            Enumeration<? extends ZipEntry> entries = zfile.entries();

            while(entries.hasMoreElements()) {
               ZipEntry entry = (ZipEntry)entries.nextElement();
               File file = new File(directory, entry.getName());
               if (entry.isDirectory()) {
                  file.mkdirs();
               } else {
                  file.getParentFile().mkdirs();
                  InputStream in = zfile.getInputStream(entry);

                  try {
                     copy(in, file);
                  } finally {
                     in.close();
                  }
               }
            }
         } finally {
            if (zfile != null) {
               zfile.close();
            }

         }

      } catch (Throwable var21) {
         if (var2 == null) {
            var2 = var21;
         } 
         throw var2;
      }
   }

   private static void copy(InputStream in, OutputStream out) throws IOException {
      byte[] buffer = new byte[1024];

      while(true) {
         int readCount = in.read(buffer);
         if (readCount < 0) {
            return;
         }

         out.write(buffer, 0, readCount);
      }
   }

   private static void copy(File file, OutputStream out) throws IOException {
      InputStream in = new FileInputStream(file);

      try {
         copy((InputStream)in, (OutputStream)out);
      } finally {
         in.close();
      }

   }

   private static void copy(InputStream in, File file) throws IOException {
      OutputStream out = new FileOutputStream(file);

      try {
         copy((InputStream)in, (OutputStream)out);
      } finally {
         out.close();
      }

   }

   public static void unzipOLD(String zipFile, String destinationFolder) {
      File directory = new File(destinationFolder);
      if (!directory.exists()) {
         directory.mkdirs();
      }

      byte[] buffer = new byte[2048];
      FileInputStream fInput = null;
      ZipInputStream zipInput = null;

      try {
         fInput = new FileInputStream(zipFile);
         zipInput = new ZipInputStream(fInput);

         for(ZipEntry entry = zipInput.getNextEntry(); entry != null; entry = zipInput.getNextEntry()) {
            String entryName = entry.getName();
            File file = new File(destinationFolder + File.separator + entryName);
            if (entry.isDirectory()) {
               File newDir = new File(file.getAbsolutePath());
               if (!newDir.exists()) {
                  newDir.mkdirs();
               }
            } else {
               FileOutputStream fOutput = new FileOutputStream(file);               

               int count;
               while((count = zipInput.read(buffer)) > 0) {
                  fOutput.write(buffer, 0, count);
               }

               fOutput.close();
            }

            zipInput.closeEntry();
         }
      } catch (IOException var23) {
         var23.printStackTrace();
      } finally {
         if (fInput != null) {
            try {
               fInput.close();
            } catch (IOException var22) {
               var22.printStackTrace();
            }
         }

         if (zipInput != null) {
            try {
               zipInput.closeEntry();
               zipInput.close();
            } catch (IOException var21) {
               var21.printStackTrace();
            }
         }

      }

   }
}
