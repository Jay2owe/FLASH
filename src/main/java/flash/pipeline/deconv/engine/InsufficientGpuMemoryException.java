package flash.pipeline.deconv.engine;

public class InsufficientGpuMemoryException extends DeconvolutionException {

    public InsufficientGpuMemoryException(String message) {
        super(message);
    }

    public InsufficientGpuMemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
