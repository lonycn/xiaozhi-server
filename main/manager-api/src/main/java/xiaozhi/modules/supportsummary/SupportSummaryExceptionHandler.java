package xiaozhi.modules.supportsummary;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import xiaozhi.modules.support.SupportExceptionHandler;

/** Reuse support's HTTP error contract without widening another module's controller advice. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={SupportSummaryController.class,SupportAssistanceController.class,SupportAssistanceWorkerController.class})
public class SupportSummaryExceptionHandler extends SupportExceptionHandler { }
