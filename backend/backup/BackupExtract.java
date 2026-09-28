import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class BackupExtract {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("usage: BackupExtract INPUT OUTPUT_DIR");
    Path root = Path.of(args[1]).toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
        Files.newInputStream(Path.of(args[0]))))) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
        Path output = safePath(root, entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(output);
          continue;
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("duplicate backup entry: " + entry.getName());
        }
        Files.createDirectories(output.getParent());
        Files.copy(zip, output);
      }
    }
  }

  private static Path safePath(Path root, String name) throws IOException {
    if (name == null || name.isBlank() || name.indexOf('\\') >= 0) {
      throw new IOException("unsafe backup path");
    }
    Path output = root.resolve(name).normalize();
    if (!output.startsWith(root)) throw new IOException("unsafe backup path: " + name);
    return output;
  }
}
