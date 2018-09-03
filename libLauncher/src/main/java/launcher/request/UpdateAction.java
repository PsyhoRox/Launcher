package launcher.request;

import java.io.IOException;

import launcher.hasher.HashedEntry;
import launcher.helper.IOHelper;
import launcher.serialize.HInput;
import launcher.serialize.HOutput;
import launcher.serialize.stream.EnumSerializer;
import launcher.serialize.stream.StreamObject;

public final class UpdateAction extends StreamObject {
    public enum Type implements EnumSerializer.Itf {
        CD(1), CD_BACK(2), GET(3), FINISH(255);
        private static final EnumSerializer<Type> SERIALIZER = new EnumSerializer<>(Type.class);
        public static Type read(HInput input) throws IOException {
            return SERIALIZER.read(input);
        }

        private final int n;

        Type(int n) {
            this.n = n;
        }

        @Override
        public int getNumber() {
            return n;
        }
    }
    public static final UpdateAction CD_BACK = new UpdateAction(Type.CD_BACK, null, null);

    public static final UpdateAction FINISH = new UpdateAction(Type.FINISH, null, null);
    // Instance
    public final Type type;
    public final String name;

    public final HashedEntry entry;

    public UpdateAction(HInput input) throws IOException {
        type = Type.read(input);
        name = type == Type.CD || type == Type.GET ? IOHelper.verifyFileName(input.readString(255)) : null;
        entry = null;
    }

    public UpdateAction(Type type, String name, HashedEntry entry) {
        this.type = type;
        this.name = name;
        this.entry = entry;
    }

    @Override
    public void write(HOutput output) throws IOException {
        EnumSerializer.write(output, type);
        if (type == Type.CD || type == Type.GET)
			output.writeString(name, 255);
    }
}
