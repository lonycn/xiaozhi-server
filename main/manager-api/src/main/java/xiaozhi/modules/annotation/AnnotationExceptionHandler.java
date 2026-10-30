package xiaozhi.modules.annotation;

/** Reuse new-API HTTP error envelopes without changing legacy controller behavior. */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@org.springframework.web.bind.annotation.RestControllerAdvice(assignableTypes=AnnotationController.class)
public class AnnotationExceptionHandler extends xiaozhi.modules.conversation.ConversationExceptionHandler { }
