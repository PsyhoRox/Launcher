package launcher.request;

import java.io.IOException;

import launcher.LauncherAPI;

public final class RequestException extends IOException {
    private static final long serialVersionUID = 7558237657082664821L;

    @LauncherAPI
    public RequestException(String message) {
        super(message);
    }

    @LauncherAPI
    public RequestException(String message, Throwable exc) {
        super(message, exc);
    }

    @LauncherAPI
    public RequestException(Throwable exc) {
        super(exc);
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
