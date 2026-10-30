package xiaozhi.modules.annotation;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.conversation.ConversationProblem;

class AnnotationSceneTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode scene()throws Exception{return (ObjectNode)json.readTree("{\"format\":\"annotation-v1\",\"frame_id\":\"123\",\"width\":1920,\"height\":1080,\"elements\":[]}");}
    ObjectNode element(String value)throws Exception{return (ObjectNode)json.readTree(value);}
    @Test void allFiveClosedShapesAndChineseTextAreAccepted()throws Exception {
        ObjectNode root=scene();ArrayNode shapes=(ArrayNode)root.get("elements");
        for(String shape:new String[]{
            "{\"id\":\"arrow\",\"type\":\"arrow\",\"color\":\"#FF0000\",\"stroke_width\":0.004,\"points\":[{\"x\":0.1,\"y\":0.2},{\"x\":0.4,\"y\":0.5}]}",
            "{\"id\":\"rect\",\"type\":\"rect\",\"color\":\"#FF0000\",\"stroke_width\":0.004,\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.4}",
            "{\"id\":\"circle\",\"type\":\"circle\",\"color\":\"#FF0000\",\"stroke_width\":0.004,\"cx\":0.5,\"cy\":0.5,\"radius\":0.2}",
            "{\"id\":\"pen\",\"type\":\"freehand\",\"color\":\"#FF0000\",\"stroke_width\":0.004,\"points\":[{\"x\":0.1,\"y\":0.2},{\"x\":0.4,\"y\":0.5}]}",
            "{\"id\":\"text\",\"type\":\"text\",\"color\":\"#FF0000\",\"x\":0.1,\"y\":0.2,\"text\":\"检查阀门 😀\",\"font_size\":0.04}"})shapes.add(element(shape));
        assertEquals(5,AnnotationScene.validate(root,"123",1920,1080,json).get("elements").size());
    }
    @Test void frameBindingUnknownFieldsAndExternalResourcesAreRejected()throws Exception {
        ObjectNode root=scene();root.put("frame_id","456");assertThrows(ConversationProblem.class,()->AnnotationScene.validate(root,"123",1920,1080,json));
        for(String field:new String[]{"url","files","src","svg","dataURL","script"}){
            ObjectNode invalid=scene();invalid.put(field,"https://external.invalid");assertThrows(ConversationProblem.class,()->AnnotationScene.validate(invalid,"123",1920,1080,json));
        }
        ObjectNode mismatch=scene();mismatch.put("width",1921);assertThrows(ConversationProblem.class,()->AnnotationScene.validate(mismatch,"123",1920,1080,json));
    }
    @Test void finiteCoordinatesTrueCircleBoundsAndStrictShapeFieldsAreEnforced()throws Exception {
        for(String mutation:new String[]{"nan","outside","unknown","bad-color","duplicate"}){
            ObjectNode root=scene(),circle=element("{\"id\":\"circle\",\"type\":\"circle\",\"color\":\"#FF0000\",\"stroke_width\":0.004,\"cx\":0.5,\"cy\":0.5,\"radius\":0.2}");
            switch(mutation){case "nan"->circle.put("cx",Double.NaN);case "outside"->circle.put("cy",.01);case "unknown"->circle.put("url","https://tracking.invalid");case "bad-color"->circle.put("color","red");default->{}}
            ((ArrayNode)root.get("elements")).add(circle);if(mutation.equals("duplicate"))((ArrayNode)root.get("elements")).add(circle.deepCopy());
            assertThrows(ConversationProblem.class,()->AnnotationScene.validate(root,"123",1920,1080,json),mutation);
        }
    }
    @Test void pointTextAndSceneComplexityAreBounded()throws Exception {
        ObjectNode root=scene();ObjectNode free=element("{\"id\":\"pen\",\"type\":\"freehand\",\"color\":\"#FF0000\",\"stroke_width\":0.004,\"points\":[]}");
        for(int i=0;i<2001;i++)((ArrayNode)free.get("points")).add(json.createObjectNode().put("x",.5).put("y",.5));((ArrayNode)root.get("elements")).add(free);assertThrows(ConversationProblem.class,()->AnnotationScene.validate(root,"123",1920,1080,json));
        ObjectNode huge=scene();ObjectNode text=element("{\"id\":\"text\",\"type\":\"text\",\"color\":\"#FF0000\",\"x\":0.1,\"y\":0.2,\"text\":\"x\",\"font_size\":0.04}");text.put("text","字".repeat(1001));((ArrayNode)huge.get("elements")).add(text);assertThrows(ConversationProblem.class,()->AnnotationScene.validate(huge,"123",1920,1080,json));
    }
    @Test void canonicalSceneHashInputIgnoresObjectFieldOrder()throws Exception {
        JsonNode a=scene(),b=json.readTree("{\"elements\":[],\"height\":1080,\"width\":1920,\"frame_id\":\"123\",\"format\":\"annotation-v1\"}");
        assertEquals(json.writeValueAsString(AnnotationScene.validate(a,"123",1920,1080,json)),json.writeValueAsString(AnnotationScene.validate(b,"123",1920,1080,json)));
    }
}
