import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.Arrays;

class BackupDecrypt {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("usage: BackupDecrypt INPUT OUTPUT");
    String secret = System.getenv("BACKUP_ENCRYPTION_SECRET");
    if (secret == null || secret.length() < 32) throw new IllegalArgumentException("BACKUP_ENCRYPTION_SECRET is required");
    try (InputStream input = new BufferedInputStream(Files.newInputStream(Path.of(args[0])))) {
      if (!Arrays.equals(input.readNBytes(5), "AFBK1".getBytes(StandardCharsets.US_ASCII)))
        throw new IllegalArgumentException("invalid backup header");
      int saltLength = ByteBuffer.wrap(input.readNBytes(4)).getInt();
      if (saltLength != 16) throw new IllegalArgumentException("invalid backup salt");
      byte[] salt = input.readNBytes(saltLength), iv = input.readNBytes(12);
      PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, 210_000, 256);
      byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      try (CipherInputStream decrypted = new CipherInputStream(input, cipher);
           OutputStream output = Files.newOutputStream(Path.of(args[1]))) {
        decrypted.transferTo(output);
      }
    }
  }
}
