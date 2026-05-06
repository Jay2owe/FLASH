package flash.pipeline.deconv.engine;

public class EnginePluginMissingException extends DeconvolutionException {

    public EnginePluginMissingException(String message) {
        super(message);
    }

    public EnginePluginMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}
