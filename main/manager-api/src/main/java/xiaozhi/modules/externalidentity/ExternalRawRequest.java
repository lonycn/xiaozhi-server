package xiaozhi.modules.externalidentity;

import java.io.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

/** Only installed after bounded reading by the earliest B6 filter; signed bytes stay unchanged. */
final class ExternalRawRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    ExternalRawRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
    @Override public int getContentLength(){return body.length;}
    @Override public long getContentLengthLong(){return body.length;}
    @Override public ServletInputStream getInputStream() {
        var input=new ByteArrayInputStream(body);
        return new ServletInputStream(){
            public int read(){return input.read();}
            public int read(byte[] value,int off,int length){return input.read(value,off,length);}
            public boolean isFinished(){return input.available()==0;}
            public boolean isReady(){return true;}
            public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous bounded input only");}
        };
    }
}
