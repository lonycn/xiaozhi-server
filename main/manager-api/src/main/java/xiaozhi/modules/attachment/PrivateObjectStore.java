package xiaozhi.modules.attachment;

import java.io.*;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

/** Only generated keys are accepted. Neither adapter creates buckets or changes bucket policy. */
public interface PrivateObjectStore {
    void put(String key, Path file, String mime) throws IOException;
    InputStream read(String key) throws IOException;
    void delete(String key) throws IOException;
    boolean exists(String key) throws IOException;
    static PrivateObjectStore configured(Environment e) {
        return switch(e.getProperty("media.storage.type", "filesystem")) {
            case "filesystem" -> new Filesystem(Path.of(e.getProperty("media.storage.root", "./data/b3-private-attachments")));
            case "s3" -> new S3(e);
            default -> throw new IllegalArgumentException("Unsupported media.storage.type");
        };
    }
    static void safe(String key) {
        if(key == null || !key.matches("[0-9]{1,19}/[a-f0-9]{32}\\.bin")) throw new IllegalArgumentException("Invalid private object key");
    }
    final class Filesystem implements PrivateObjectStore {
        private final Path root;
        public Filesystem(Path root) {
            Path configured=root.toAbsolutePath().normalize();
            try {
                Files.createDirectories(configured);
                if(Files.isSymbolicLink(configured)) throw new IOException("Symlink root");
                this.root=configured.toRealPath();
                try { Files.setPosixFilePermissions(this.root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
                catch(UnsupportedOperationException ignored) { }
            } catch(IOException e) { throw new IllegalStateException("Private media storage unavailable",e); }
        }
        private Path path(String key) throws IOException {
            safe(key); Path p=root.resolve(key).normalize();
            if(!p.startsWith(root) || Files.isSymbolicLink(p) || Files.isSymbolicLink(p.getParent())) throw new IOException("Unsafe object path");
            return p;
        }
        public void put(String key, Path file, String mime) throws IOException {
            Path p=path(key); Files.createDirectories(p.getParent());
            if(!p.getParent().toRealPath().startsWith(root)) throw new IOException("Unsafe object parent");
            // CREATE_NEW and a generated per-attempt key make originals and artifacts immutable.
            try(OutputStream out=Files.newOutputStream(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { Files.copy(file,out); }
            try { Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch(UnsupportedOperationException ignored) { }
        }
        public InputStream read(String key) throws IOException { return Files.newInputStream(path(key),LinkOption.NOFOLLOW_LINKS); }
        public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }
        public boolean exists(String key) throws IOException { return Files.isRegularFile(path(key),LinkOption.NOFOLLOW_LINKS); }
    }
    final class S3 implements PrivateObjectStore {
        private final S3Client client;
        private final String bucket, prefix;
        public S3(Environment e) {
            bucket=required(e,"media.storage.s3.bucket"); prefix=required(e,"media.storage.s3.prefix");
            if(!prefix.matches("[a-zA-Z0-9_-][a-zA-Z0-9_/-]{1,180}/") || prefix.contains("..") || prefix.contains("//")) throw new IllegalArgumentException("Dedicated S3 prefix required");
            String endpoint=required(e,"media.storage.s3.endpoint"); URI uri=URI.create(endpoint);
            if(!(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null) throw new IllegalArgumentException("Invalid S3 endpoint");
            client=S3Client.builder().endpointOverride(uri).region(Region.of(e.getProperty("media.storage.s3.region","us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required(e,"media.storage.s3.access-key"),required(e,"media.storage.s3.secret-key"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(40))).build();
        }
        private static String required(Environment e,String key) { String s=e.getProperty(key); if(s==null || s.isBlank()) throw new IllegalArgumentException("Missing "+key); return s; }
        private String full(String key) { safe(key); return prefix+key; }
        public void put(String key,Path file,String mime) throws IOException {
            try { client.putObject(PutObjectRequest.builder().bucket(bucket).key(full(key)).contentType(mime).build(),RequestBody.fromFile(file)); }
            catch(RuntimeException ex) { throw new IOException("Private S3 write failed"); }
        }
        public InputStream read(String key) throws IOException {
            try { return client.getObject(GetObjectRequest.builder().bucket(bucket).key(full(key)).build()); }
            catch(RuntimeException ex) { throw new IOException("Private S3 read failed"); }
        }
        public void delete(String key) throws IOException {
            try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(full(key)).build()); }
            catch(RuntimeException ex) { throw new IOException("Private S3 delete failed"); }
        }
        public boolean exists(String key) throws IOException {
            try { client.headObject(HeadObjectRequest.builder().bucket(bucket).key(full(key)).build()); return true; }
            catch(NoSuchKeyException ex) { return false; }
            catch(S3Exception ex) { if(ex.statusCode()==404) return false; throw new IOException("Private S3 stat failed"); }
            catch(RuntimeException ex) { throw new IOException("Private S3 stat failed"); }
        }
    }
}
