package launcher.modules;


import launcher.LauncherVersion;

public class TestClientModule implements Module {
    @Override
    public void close() throws Exception {

    }

    @Override
    public String getName() {
        return "TestClientModule";
    }

    @Override
    public LauncherVersion getVersion() {
        return LauncherVersion.getVersion();
    }

    @Override
    public void init(ModuleContext context) {

    }

    @Override
    public void postInit(ModuleContext context) {

    }

    @Override
    public void preInit(ModuleContext context) {

    }
}
