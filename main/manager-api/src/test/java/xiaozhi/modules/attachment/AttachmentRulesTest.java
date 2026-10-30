package xiaozhi.modules.attachment;

import static org.junit.jupiter.api.Assertions.*;
import static xiaozhi.modules.attachment.AttachmentRules.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.mock.env.MockEnvironment;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.mockito.Mockito;
import xiaozhi.modules.conversation.*;
import xiaozhi.modules.security.config.ShiroConfig;
import xiaozhi.modules.sys.service.SysParamsService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class AttachmentRulesTest {
    @TempDir Path temp;
    static final String KEY="synthetic-b3-capability-key-32-characters";
    @Test void capabilitiesAreBoundToActorResourceVariantAnd120SecondExpiry() {
        long now=Instant.now().getEpochSecond();String token=ticket(KEY,7,123,"display",now+120);
        assertEquals(new Ticket(7,"display"),verify(KEY,token,123,now));
        assertThrows(ConversationProblem.class,()->verify(KEY,token,124,now));
        assertThrows(ConversationProblem.class,()->verify(KEY,token,123,now+120));
        assertThrows(ConversationProblem.class,()->verify(KEY,token+"x",123,now));
        assertThrows(ConversationProblem.class,()->verify(KEY,ticket(KEY,7,123,"original",now+121),123,now));
        assertThrows(ConversationProblem.class,()->ticket("short",7,123,"display",now+1));
    }
    @Test void streamsAreBoundedAndHashedWithoutLoadingWholeFiles()throws Exception {
        byte[] value="private evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);Path path;
        try(Staged staged=stage(new ByteArrayInputStream(value),value.length)) {
            path=staged.file();assertArrayEquals(value,Files.readAllBytes(path));assertEquals(value.length,staged.size());assertEquals(64,staged.sha256().length());
        }assertFalse(Files.exists(path));
        assertEquals(413,assertThrows(ConversationProblem.class,()->stage(new ByteArrayInputStream(value),2)).status());
        assertThrows(ConversationProblem.class,()->stage(new ByteArrayInputStream(new byte[0]),3));
    }
    @Test void privateFilesystemRejectsTraversalSymlinksAndReplacement()throws Exception {
        var store=new PrivateObjectStore.Filesystem(temp.resolve("private"));Path staged=temp.resolve("source");Files.writeString(staged,"evidence");String key="123/0123456789abcdef0123456789abcdef.bin";
        store.put(key,staged,"text/plain");assertTrue(store.exists(key));
        try(var input=store.read(key)){assertEquals("evidence",new String(input.readAllBytes()));}
        assertThrows(IOException.class,()->store.put(key,staged,"text/plain"));
        assertThrows(IllegalArgumentException.class,()->store.read("../outside"));
        Path outside=temp.resolve("outside");Files.writeString(outside,"preserve");
        Files.createSymbolicLink(temp.resolve("private/123/11111111111111111111111111111111.bin"),outside);
        assertThrows(IOException.class,()->store.read("123/11111111111111111111111111111111.bin"));
        assertThrows(IOException.class,()->store.delete("123/11111111111111111111111111111111.bin"));
        store.delete(key);assertFalse(store.exists(key));assertEquals("preserve",Files.readString(outside));
    }
    @Test void storageConfigurationNeverFallsBackOnMisconfiguredS3() {
        assertThrows(IllegalArgumentException.class,()->PrivateObjectStore.configured(new MockEnvironment().withProperty("media.storage.type","s3")));
        assertThrows(IllegalArgumentException.class,()->PrivateObjectStore.configured(new MockEnvironment().withProperty("media.storage.type","public")));
    }
    @Test void strictRangeValidationSupportsSuffixOpenEndedAnd416() {
        assertEquals(new ByteRange(0,9),range(null,10));assertEquals(new ByteRange(2,5),range("bytes=2-5",10));
        assertEquals(new ByteRange(8,9),range("bytes=-2",10));assertEquals(new ByteRange(8,9),range("bytes=8-",10));
        for(String value:List.of("bytes=10-","bytes=2-1","bytes=-0","bytes=0-1,3-4","bytes=-","bytes=999999999999999999999-"))assertEquals(416,assertThrows(ConversationProblem.class,()->range(value,10)).status());
    }
    @Test void extensionsPathsAndUnsupportedActiveDocumentsAreRejected() {
        assertEquals("image",kind("image/png"));assertEquals(100*MIB,max("video/mp4"));
        assertEquals("evidence.JPG",filename("evidence.JPG","image/jpeg"));
        for(String bad:List.of("x.png/evil.jpg","../evil.jpg","x\r\n.jpg","x.svg"))assertThrows(ConversationProblem.class,()->filename(bad,"image/jpeg"));
        for(String bad:List.of("image/svg+xml","text/html","image/gif","application/zip"))assertThrows(ConversationProblem.class,()->kind(bad));
    }
    @Test void messagePartRetainsEightArgumentConstructorAndRejectsSpoofedMetadata()throws Exception {
        var mapper=JsonMapper.builder().addModule(new JavaTimeModule()).build();var dao=Mockito.mock(ConversationDao.class);
        var service=new ConversationService(dao,mapper,Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        var legacy=new ConversationModels.MessagePart("text","hello",null,null,null,null,null,null);
        assertFalse(mapper.writeValueAsString(legacy).contains("attachment"));
        var parsed=service.request(mapper.readTree("{\"type\":\"image\",\"attachment_id\":\"123\"}"),ConversationModels.MessagePart.class);
        assertEquals("123",parsed.attachmentId());
        assertThrows(ConversationProblem.class,()->service.request(mapper.readTree("{\"type\":\"image\",\"attachment_id\":\"123\",\"url\":\"https://evil\"}"),ConversationModels.MessagePart.class));
    }
    @Test void earlyShiroReadsDedicatedMediaKeyAndOnlyPreciseContentRouteIsAnonymous()throws Exception {
        ShiroFilterFactoryBean factory=ShiroConfig.shiroFilterWithWorker(Mockito.mock(WebSecurityManager.class),Mockito.mock(SysParamsService.class),new MockEnvironment().withProperty("media.worker.key",KEY).withProperty("ai.worker.key","different-ai-key-which-is-at-least-32-chars"));
        var worker=(org.apache.shiro.web.filter.AccessControlFilter)factory.getFilters().get("mediaWorker");
        var request=new MockHttpServletRequest();request.addHeader("X-Media-Worker-Key",KEY);assertTrue(worker.onPreHandle(request,new MockHttpServletResponse(),null));
        request.removeHeader("X-Media-Worker-Key");request.addHeader("Authorization","Bearer "+KEY);var denied=new MockHttpServletResponse();assertFalse(worker.onPreHandle(request,denied,null));assertEquals(401,denied.getStatus());
        request.addHeader("X-AI-Worker-Key",KEY);assertFalse(worker.onPreHandle(request,new MockHttpServletResponse(),null));
        assertEquals("mediaWorker",factory.getFilterChainDefinitionMap().get("/internal/media-worker/**"));
        assertEquals("anon",factory.getFilterChainDefinitionMap().get("/api/v1/ai/attachments/*/content"));
        assertEquals("conversation",factory.getFilterChainDefinitionMap().get("/api/v1/ai/**"));
        var keys=new ArrayList<>(factory.getFilterChainDefinitionMap().keySet());assertTrue(keys.indexOf("/api/v1/ai/attachments/*/content")<keys.indexOf("/api/v1/ai/**"));
    }
    @Test void authorizedMediaResponseSupports206And416WithoutContentLeak()throws Exception {
        var request=new MockHttpServletRequest();request.addHeader("Range","bytes=2-4");var response=new MockHttpServletResponse();
        try(var content=new AttachmentService.Content(new ByteArrayInputStream("0123456789".getBytes()),10,"audio/wav","evidence.wav",false)){AttachmentController.write(content,request,response,true);}
        assertEquals(206,response.getStatus());assertEquals("234",response.getContentAsString());assertEquals("bytes 2-4/10",response.getHeader("Content-Range"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertEquals("private, no-store",response.getHeader("Cache-Control"));
        request.removeHeader("Range");request.addHeader("Range","bytes=10-");response=new MockHttpServletResponse();
        try(var content=new AttachmentService.Content(new ByteArrayInputStream("0123456789".getBytes()),10,"audio/wav","evidence.wav",false)){AttachmentController.write(content,request,response,true);}
        assertEquals(416,response.getStatus());assertEquals(0,response.getContentAsByteArray().length);
    }
}
