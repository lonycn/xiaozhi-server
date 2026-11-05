package xiaozhi.modules.modeltest;

import java.util.function.BiConsumer;
import xiaozhi.modules.modeltest.ModelTestModels.Outcome;
import xiaozhi.modules.modeltest.ModelTestModels.Probe;

public interface ModelProbeExecutor {
    Outcome execute(Probe probe, BiConsumer<String, String> log) throws Exception;
}
