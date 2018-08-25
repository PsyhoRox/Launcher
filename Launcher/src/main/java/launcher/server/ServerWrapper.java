package launcher.server;


import launcher.Launcher;
import launcher.LauncherConfig;
import launcher.client.ClientLauncher;
import launcher.helper.IOHelper;
import launcher.profiles.ClientProfile;
import launcher.request.Request;
import launcher.request.update.LauncherRequest;
import launcher.serialize.HInput;
import launcher.serialize.signed.SignedObjectHolder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Paths;

public class ServerWrapper {
    public ClientProfile profile;
    public static ModulesManager modulesManager;
    public static void main(String[] args) throws Throwable {
        ServerWrapper wrapper = new ServerWrapper();
        modulesManager = new ModulesManager(wrapper);
        modulesManager.autoload(Paths.get("modules"));
        LauncherConfig cfg = new LauncherConfig(new HInput(IOHelper.newInput(IOHelper.getResourceURL(Launcher.CONFIG_FILE))));
        LauncherRequest.Result result = new LauncherRequest(cfg).request();
        for(SignedObjectHolder<ClientProfile> p : result.profiles)
        {
            if(p.object.getTitle() == ClientLauncher.title) {
                wrapper.profile = p.object;
                break;
            }
        }

        String classname = args[0];
        Class mainClass = Class.forName(classname);
        MethodHandle mainMethod = MethodHandles.publicLookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class));
        String[] real_args = new String[args.length - 1];
        System.arraycopy(args,1,real_args,0,args.length - 1);
        mainMethod.invoke(real_args);
    }
}
