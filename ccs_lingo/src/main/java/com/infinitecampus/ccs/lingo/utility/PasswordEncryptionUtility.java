package com.infinitecampus.ccs.lingo.utility;

import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class PasswordEncryptionUtility {
   //private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
   //private static final String CHARSET = "UTF-8";

   private static IvParameterSpec getFixedIv() {
      byte[] iv = new byte[16];
      return new IvParameterSpec(iv);
   }

   private static SecretKeySpec getFixedKey() {
      String key = "Cust0m70";
      byte[] keyBytes = key.getBytes();
      byte[] keyBytesPadded = new byte[16];
      System.arraycopy(keyBytes, 0, keyBytesPadded, 0, Math.min(keyBytes.length, keyBytesPadded.length));
      return new SecretKeySpec(keyBytesPadded, "AES");
   }

   public static String encrypt(String plainPassword)throws Exception {
   //, SecretKey key, IvParameterSpec iv) throws Exception {
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    //  cipher.init(1, key, iv);
      cipher.init(1, getFixedKey(),getFixedIv());
      byte[] encryptedBytes = cipher.doFinal(plainPassword.getBytes("UTF-8"));
      return Base64.getEncoder().encodeToString(encryptedBytes);
   }

   public static String decrypt(String encryptedPassword) throws Exception {
      SecretKeySpec key = getFixedKey();
      IvParameterSpec iv = getFixedIv();
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(2, key, iv);
      byte[] decodedBytes = Base64.getDecoder().decode(encryptedPassword);
      byte[] decryptedBytes = cipher.doFinal(decodedBytes);
      return new String(decryptedBytes, "UTF-8");
   }
}
