package dev.feddi.federation.app;

/**
 * Exception thrown when ZIP uploads are disabled because an extension-provided definition source is active.
 */
public class ZipUploadNotSupportedException extends RuntimeException {
    public ZipUploadNotSupportedException(String message) {
        super(message);
    }
}
