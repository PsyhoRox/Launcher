package launchserver.texture;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import launcher.LauncherAPI;
import launcher.helper.VerifyHelper;
import launcher.profiles.Texture;
import launcher.serialize.config.entry.BlockConfigEntry;

public final class NullTextureProvider extends TextureProvider {
    private volatile TextureProvider provider;

    public NullTextureProvider(BlockConfigEntry block) {
        super(block);
    }

    @Override
    public void close() throws IOException {
        TextureProvider provider = this.provider;
        if (provider != null)
			provider.close();
    }

    @Override
    public Texture getCloakTexture(UUID uuid, String username, String client) throws IOException {
        return getProvider().getCloakTexture(uuid, username, client);
    }

    private TextureProvider getProvider() {
        return VerifyHelper.verify(provider, Objects::nonNull, "Backend texture provider wasn't set");
    }

    @Override
    public Texture getSkinTexture(UUID uuid, String username, String client) throws IOException {
        return getProvider().getSkinTexture(uuid, username, client);
    }

    @LauncherAPI
    public void setBackend(TextureProvider provider) {
        this.provider = provider;
    }
}
