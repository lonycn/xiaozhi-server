package xiaozhi.modules.annotation;

import static xiaozhi.modules.attachment.AttachmentRules.problem;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

/** Closed, framework-independent scene format. Never accepts browser scene objects or image resources. */
public final class AnnotationScene {
    private AnnotationScene() { }
    private static final Set<String> ROOT=Set.of("format","frame_id","width","height","elements");
    private static final Set<String> COMMON=Set.of("id","type","color","stroke_width");
    public static JsonNode validate(JsonNode scene,String frameId,int width,int height,ObjectMapper json) {
        if(scene==null || !scene.isObject())throw problem(400,"INVALID_ANNOTATION_SCENE");
        try {if(json.writeValueAsBytes(scene).length>256*1024)throw problem(413,"ANNOTATION_SCENE_LIMIT");}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw problem(400,"INVALID_ANNOTATION_SCENE");}
        fields(scene,ROOT);if(!"annotation-v1".equals(text(scene,"format")) || !frameId.equals(text(scene,"frame_id")) || integer(scene,"width")!=width || integer(scene,"height")!=height)throw problem(400,"ANNOTATION_FRAME_MISMATCH");
        JsonNode elements=scene.get("elements");if(elements==null || !elements.isArray() || elements.size()>200)throw problem(400,"ANNOTATION_SCENE_LIMIT");
        Set<String> ids=new HashSet<>();int pointCount=0,textCount=0;
        for(JsonNode element:elements) {
            String type=text(element,"type"),id=text(element,"id"),color=text(element,"color");
            if(!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id) || !color.matches("#[a-fA-F0-9]{6}"))throw problem(400,"INVALID_ANNOTATION_ELEMENT");
            switch(type) {
                case "arrow","freehand" -> {
                    fields(element,with(COMMON,"points"));stroke(element);JsonNode points=element.get("points");
                    if(points==null || !points.isArray() || points.size()<2 || points.size()>(type.equals("arrow")?2:2000))throw problem(400,"ANNOTATION_POINT_LIMIT");
                    pointCount+=points.size();for(JsonNode p:points){fields(p,Set.of("x","y"));coordinate(p,"x");coordinate(p,"y");}
                }
                case "rect" -> {
                    fields(element,with(COMMON,"x","y","width","height"));stroke(element);double x=coordinate(element,"x"),y=coordinate(element,"y"),w=positive(element,"width"),h=positive(element,"height");
                    if(x+w>1+1e-12 || y+h>1+1e-12)throw problem(400,"ANNOTATION_OUT_OF_BOUNDS");
                }
                case "circle" -> {
                    fields(element,with(COMMON,"cx","cy","radius"));stroke(element);double x=coordinate(element,"cx"),y=coordinate(element,"cy"),radius=positive(element,"radius")*Math.min(width,height);
                    if(x*width-radius<0 || x*width+radius>width || y*height-radius<0 || y*height+radius>height)throw problem(400,"ANNOTATION_OUT_OF_BOUNDS");
                }
                case "text" -> {
                    fields(element,Set.of("id","type","color","x","y","text","font_size"));coordinate(element,"x");coordinate(element,"y");number(element,"font_size",.005,.1);
                    String value=text(element,"text");int length=value.codePointCount(0,value.length());
                    if(value.isBlank() || length>1000 || value.codePoints().anyMatch(c->(c<32 && c!='\n' && c!='\t') || c==127 || (c>=0xD800 && c<=0xDFFF)))throw problem(400,"INVALID_ANNOTATION_TEXT");textCount+=length;
                    // The worker checks actual glyph bounds using the fixed local font before rendering.
                }
                default -> throw problem(400,"INVALID_ANNOTATION_ELEMENT");
            }
            if(pointCount>20000 || textCount>8000)throw problem(400,"ANNOTATION_SCENE_LIMIT");
        }
        return canonical(scene,json);
    }
    private static Set<String> with(Set<String> base,String... extra){Set<String> s=new HashSet<>(base);Collections.addAll(s,extra);return s;}
    private static void fields(JsonNode node,Set<String> allowed) {
        if(node==null || !node.isObject() || node.size()!=allowed.size())throw problem(400,"INVALID_ANNOTATION_SCENE");
        var keys=node.fieldNames();while(keys.hasNext())if(!allowed.contains(keys.next()))throw problem(400,"INVALID_ANNOTATION_SCENE");
    }
    private static String text(JsonNode node,String key){JsonNode value=node==null?null:node.get(key);if(value==null || !value.isTextual())throw problem(400,"INVALID_ANNOTATION_SCENE");return value.textValue();}
    private static long integer(JsonNode node,String key){JsonNode value=node.get(key);if(value==null || !value.isIntegralNumber() || !value.canConvertToInt())throw problem(400,"INVALID_ANNOTATION_SCENE");return value.longValue();}
    private static double coordinate(JsonNode node,String key){return number(node,key,0,1);}
    private static double positive(JsonNode node,String key){double value=coordinate(node,key);if(value<=0)throw problem(400,"INVALID_ANNOTATION_SCENE");return value;}
    private static double number(JsonNode node,String key,double min,double max){JsonNode value=node.get(key);if(value==null || !value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue()<min || value.doubleValue()>max)throw problem(400,"INVALID_ANNOTATION_SCENE");return value.doubleValue();}
    private static void stroke(JsonNode element){number(element,"stroke_width",.001,.02);}
    /** Stable field order and numeric values make same-content retry independent of JSON key order. */
    private static JsonNode canonical(JsonNode node,ObjectMapper json) {
        if(node.isObject()){ObjectNode result=json.createObjectNode();TreeSet<String> keys=new TreeSet<>();node.fieldNames().forEachRemaining(keys::add);for(String key:keys)result.set(key,canonical(node.get(key),json));return result;}
        if(node.isArray()){ArrayNode result=json.createArrayNode();for(JsonNode child:node)result.add(canonical(child,json));return result;}
        if(node.isNumber()){if(node.isIntegralNumber())return node;return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());}
        return node;
    }
}
