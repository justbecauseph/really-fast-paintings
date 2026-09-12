package me.justbecause.fastpaintings.painting;

public class RestorationException extends RuntimeException {

    public RestorationException(String message) {
        super(message);
    }

    public RestorationException(String message, Throwable cause) {
        super(message, cause);
    }
}
