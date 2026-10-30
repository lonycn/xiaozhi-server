package xiaozhi.modules.attachment;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import xiaozhi.modules.conversation.ConversationProblem;

/** Pure bounds and capability validation, shared by HTTP and persistence tests. */
public final class AttachmentRules {
    private AttachmentRules() { }
    public static final long MIB=1024*1024;
    public static final Set<String> VARIANTS=Set.of("original","display","thumbnail","text","poster","audio","transcript","frame_0","frame_1","frame_2","frame_3","frame_4","frame_5");
    public static final Set<String> ERRORS=Set.of("INVALID_MEDIA","TYPE_MISMATCH","PIXEL_LIMIT","DURATION_LIMIT","PAGE_LIMIT","PROCESSING_TIMEOUT","PROCESSING_FAILED","STORAGE_UNAVAILABLE","TRANSCRIPTION_NOT_CONFIGURED","PROVIDER_AUTH","PROVIDER_RATE_LIMIT","PROVIDER_TIMEOUT","PROVIDER_ERROR");
    public static String kind(String mime) {
        if(mime==null) throw problem(415,"UNSUPPORTED_MEDIA_TYPE");
        return switch(mime) {
            case "image/jpeg","image/png","image/webp" -> "image";
            case "application/pdf","text/plain" -> "file";
            case "video/mp4","video/webm" -> "video";
            case "audio/wav","audio/mpeg","audio/mp4","audio/ogg" -> "audio";
            default -> throw problem(415,"UNSUPPORTED_MEDIA_TYPE");
        };
    }
    public static long max(String mime) { return switch(kind(mime)) {case "video"->100*MIB;case "audio"->50*MIB;default->20*MIB;}; }
    public static String filename(String name,String mime) {
        if(name==null || name.isBlank() || name.length()>255 || name.chars().anyMatch(c->c<32 || c==127 || c=='/' || c=='\\')) throw problem(400,"INVALID_FILENAME");
        String lower=name.toLowerCase(Locale.ROOT);
        List<String> extensions=switch(mime) {
            case "image/jpeg"->List.of(".jpg",".jpeg");case "image/png"->List.of(".png");case "image/webp"->List.of(".webp");
            case "application/pdf"->List.of(".pdf");case "text/plain"->List.of(".txt");case "video/mp4"->List.of(".mp4");case "video/webm"->List.of(".webm");
            case "audio/wav"->List.of(".wav");case "audio/mpeg"->List.of(".mp3");case "audio/mp4"->List.of(".m4a");case "audio/ogg"->List.of(".ogg");
            default->throw problem(415,"UNSUPPORTED_MEDIA_TYPE");
        };
        if(extensions.stream().noneMatch(lower::endsWith)) throw problem(415,"TYPE_MISMATCH"); return name;
    }
    public static long id(String value) {
        try { if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();long id=Long.parseLong(value); if(id<=0)throw new IllegalArgumentException();return id; }
        catch(IllegalArgumentException e) { throw problem(404,"ATTACHMENT_NOT_FOUND"); }
    }
    public record Staged(Path file,long size,String sha256) implements AutoCloseable {
        public void close() throws IOException { Files.deleteIfExists(file); }
    }
    public static Staged stage(InputStream input,long max) {return stage(input,max,false);}
    public static Staged stage(InputStream input,long max,boolean allowEmpty) {
        Path file=null;
        try {
            file=Files.createTempFile("b3-media-", ".bin");
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); long size=0;
            try(OutputStream out=Files.newOutputStream(file)) {
                byte[] buf=new byte[65536];int n;
                while((n=input.read(buf))!=-1) { size+=n;if(size>max)throw problem(413,"MEDIA_SIZE_LIMIT");digest.update(buf,0,n);out.write(buf,0,n); }
            }
            if(size==0 && !allowEmpty)throw problem(400,"EMPTY_MEDIA");
            return new Staged(file,size,HexFormat.of().formatHex(digest.digest()));
        } catch(IOException e) { if(file!=null)try{Files.deleteIfExists(file);}catch(IOException ignored){}throw problem(503,"STORAGE_UNAVAILABLE"); }
        catch(RuntimeException | NoSuchAlgorithmException e) { if(file!=null)try{Files.deleteIfExists(file);}catch(IOException ignored){}if(e instanceof RuntimeException r)throw r;throw new IllegalStateException(e); }
    }
    public static String artifactMime(String variant) {
        if(!VARIANTS.contains(variant) || variant.equals("original")) throw problem(400,"INVALID_VARIANT");
        return switch(variant) {case "text","transcript"->"text/plain";case "audio"->"audio/wav";default->"image/jpeg";};
    }
    public static long artifactMax(String variant) { return switch(variant) {case "audio"->20*MIB;case "text","transcript"->240000;case "thumbnail"->MIB;case "poster","frame_0","frame_1","frame_2","frame_3","frame_4","frame_5"->2*MIB;default->20*MIB;}; }
    public static String ticket(String key,long actor,long id,String variant,long expires) {
        if(key==null || key.getBytes(StandardCharsets.UTF_8).length<32)throw problem(503,"MEDIA_TICKETS_NOT_CONFIGURED");
        if(!VARIANTS.contains(variant))throw problem(400,"INVALID_VARIANT");
        String payload=actor+":"+id+":"+variant+":"+expires;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII))+"."+sign(key,payload);
    }
    public record Ticket(long actor,String variant) { }
    public static Ticket verify(String key,String ticket,long expected,long now) {
        try {
            if(ticket==null || ticket.length()>512)throw new IllegalArgumentException();
            String[] pieces=ticket.split("\\.",-1);if(pieces.length!=2)throw new IllegalArgumentException();
            String payload=new String(Base64.getUrlDecoder().decode(pieces[0]),StandardCharsets.US_ASCII);
            if(key==null || key.getBytes(StandardCharsets.UTF_8).length<32 || !MessageDigest.isEqual(sign(key,payload).getBytes(StandardCharsets.US_ASCII),pieces[1].getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException();
            String[] values=payload.split(":",-1);if(values.length!=4)throw new IllegalArgumentException();
            long actor=Long.parseLong(values[0]), id=Long.parseLong(values[1]),exp=Long.parseLong(values[3]);
            if(actor<=0 || id!=expected || exp<=now || exp>now+120 || !VARIANTS.contains(values[2]))throw new IllegalArgumentException();
            return new Ticket(actor,values[2]);
        } catch(RuntimeException ex) { throw problem(401,"INVALID_MEDIA_TICKET"); }
    }
    private static String sign(String key,String payload) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII))); }
        catch(GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    public record ByteRange(long start,long end) { public long length(){return end-start+1;} }
    public static ByteRange range(String value,long size) {
        if(value==null)return new ByteRange(0,size-1);
        try {
            if(!value.matches("bytes=[0-9]*-[0-9]*"))throw new IllegalArgumentException();
            String[] parts=value.substring(6).split("-",-1); long start,end;
            if(parts[0].isEmpty()) {long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);end=size-1;}
            else { start=Long.parseLong(parts[0]);end=parts[1].isEmpty()?size-1:Math.min(Long.parseLong(parts[1]),size-1); }
            if(start<0 || start>=size || end<start)throw new IllegalArgumentException();return new ByteRange(start,end);
        }catch(IllegalArgumentException ex){throw problem(416,"RANGE_NOT_SATISFIABLE");}
    }
    public static ConversationProblem problem(int status,String code){return new ConversationProblem(status,code);}
}
