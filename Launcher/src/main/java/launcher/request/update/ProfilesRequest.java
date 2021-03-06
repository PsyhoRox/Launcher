package launcher.request.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import launcher.LauncherAPI;
import launcher.LauncherConfig;
import launcher.profiles.ClientProfile;
import launcher.request.Request;
import launcher.request.RequestType;
import launcher.serialize.HInput;
import launcher.serialize.HOutput;
import launcher.serialize.signed.SignedObjectHolder;

public final class ProfilesRequest extends Request<ProfilesRequest.Result> {

    public static final class Result {
        @LauncherAPI
        public final List<SignedObjectHolder<ClientProfile>> profiles;

        private Result(List<SignedObjectHolder<ClientProfile>> profiles) {
            this.profiles = Collections.unmodifiableList(profiles);
        }
    }

    @LauncherAPI
    public ProfilesRequest() {
        this(null);
    }

    @LauncherAPI
    public ProfilesRequest(LauncherConfig config) {
        super(config);
    }

    @Override
    public Integer getType() {
        return RequestType.PROFILES.getNumber();
    }
    @Override
    protected Result requestDo(HInput input, HOutput output) throws Exception {
        output.writeBoolean(true);
        output.flush();
        readError(input);

        int count = input.readLength(0);
        List<SignedObjectHolder<ClientProfile>> profiles = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
			profiles.add(new SignedObjectHolder<>(input, config.publicKey, ClientProfile.RO_ADAPTER));

        // Return request result
        return new Result(profiles);
    }
}
