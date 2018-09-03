package launcher.serialize.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import launcher.LauncherAPI;
import launcher.helper.IOHelper;
import launcher.serialize.HInput;
import launcher.serialize.HOutput;

public abstract class StreamObject {
    /* public StreamObject(HInput input) */

    @FunctionalInterface
    public interface Adapter<O extends StreamObject> {
        @LauncherAPI
        O convert(HInput input) throws IOException;
    }

    @LauncherAPI
    public final byte[] write() throws IOException {
        try (ByteArrayOutputStream array = IOHelper.newByteArrayOutput()) {
            try (HOutput output = new HOutput(array)) {
                write(output);
            }
            return array.toByteArray();
        }
    }

    @LauncherAPI
    public abstract void write(HOutput output) throws IOException;
}
