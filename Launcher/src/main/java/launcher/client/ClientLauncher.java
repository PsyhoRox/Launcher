package launcher.client;

import java.io.*;
import java.lang.ProcessBuilder.Redirect;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.WriterConfig;
import launcher.Launcher;
import launcher.LauncherClassLoader;
import launcher.LauncherConfig;
import launcher.LauncherAPI;
import launcher.client.ClientProfile.Version;
import launcher.hasher.DirWatcher;
import launcher.hasher.FileNameMatcher;
import launcher.hasher.HashedDir;
import launcher.helper.*;
import launcher.helper.JVMHelper.OS;
import launcher.request.update.LauncherRequest;
import launcher.serialize.HInput;
import launcher.serialize.HOutput;
import launcher.serialize.stream.StreamObject;
import launcher.AvanguardStarter;

public final class ClientLauncher {
    private static final String[] EMPTY_ARRAY = new String[0];
    private static final String MAGICAL_INTEL_OPTION = "-XX:HeapDumpPath=ThisTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump";
    @SuppressWarnings("unused")
	private static final Set<PosixFilePermission> BIN_POSIX_PERMISSIONS = Collections.unmodifiableSet(EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, // Owner
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, // Group
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE // Others
    ));

    // Constants
    private static final Path NATIVES_DIR = IOHelper.toPath("natives");
    private static final Path RESOURCEPACKS_DIR = IOHelper.toPath("resourcepacks");
    private static LauncherClassLoader classLoader;
    // Authlib constants
    @LauncherAPI public static final String SKIN_URL_PROPERTY = "skinURL";
    @LauncherAPI public static final String SKIN_DIGEST_PROPERTY = "skinDigest";
    @LauncherAPI public static final String CLOAK_URL_PROPERTY = "cloakURL";
    @LauncherAPI public static final String CLOAK_DIGEST_PROPERTY = "cloakDigest";

    // Used to determine from clientside is launched from launcher
    private static final AtomicBoolean LAUNCHED = new AtomicBoolean(false);

    private ClientLauncher() {
    }

    @LauncherAPI
    public static boolean isLaunched() {
        return LAUNCHED.get();
    }

    @LauncherAPI
    public static Process launch(
        HashedDir assetHDir, HashedDir clientHDir,
        ClientProfile profile, Params params, boolean pipeOutput) throws Throwable {
        // Write params file (instead of CLI; Mustdie32 API can't handle command line > 32767 chars)
        LogHelper.debug("Writing ClientLauncher params");
        Path paramsFile = Files.createTempFile("ClientLauncherParams", ".bin");
        try (HOutput output = new HOutput(IOHelper.newOutput(paramsFile))) {
            params.write(output);
            profile.write(output);
            assetHDir.write(output);
            clientHDir.write(output);
        }

        // Resolve java bin and set permissions
        LogHelper.debug("Resolving JVM binary");
        //Path javaBin = IOHelper.resolveJavaBin(jvmDir);

        // Fill CLI arguments
        List<String> args = new LinkedList<>();
        Path javaBin = Paths.get(System.getProperty("java.home")+IOHelper.PLATFORM_SEPARATOR+"bin"+IOHelper.PLATFORM_SEPARATOR+"java");
        args.add(javaBin.toString());
        args.add(MAGICAL_INTEL_OPTION);
        if (params.ram > 0 && params.ram <= JVMHelper.RAM) {
            args.add("-Xms" + params.ram + 'M');
            args.add("-Xmx" + params.ram + 'M');
        }
        args.add(Launcher.jvmProperty(LogHelper.DEBUG_PROPERTY, Boolean.toString(LogHelper.isDebugEnabled())));
        if (LauncherConfig.ADDRESS_OVERRIDE != null) {
            args.add(Launcher.jvmProperty(LauncherConfig.ADDRESS_OVERRIDE_PROPERTY, LauncherConfig.ADDRESS_OVERRIDE));
        }
        if (JVMHelper.OS_TYPE == OS.MUSTDIE && JVMHelper.OS_VERSION.startsWith("10.")) {
            LogHelper.debug("MustDie 10 fix is applied");
            args.add(Launcher.jvmProperty("os.name", "Windows 10"));
            args.add(Launcher.jvmProperty("os.version", "10.0"));
        }

        // Add classpath and main class
        StringBuilder classPathString = new StringBuilder(IOHelper.getCodeSource(ClientLauncher.class).toString());
        LinkedList<Path> classPath = resolveClassPathList(params.clientDir, profile.getClassPath());
        for (Path path : classPath) {
            classPathString.append(File.pathSeparatorChar).append(path.toString());
        }
        Collections.addAll(args, profile.getJvmArgs());
        Collections.addAll(args,"-Djava.library.path=".concat(params.clientDir.resolve(NATIVES_DIR).toString())); // Add Native Path
        //Collections.addAll(args,"-javaagent:launcher.LauncherAgent");
        //Collections.addAll(args, "-classpath", classPathString.toString());
        Collections.addAll(args, ClientLauncher.class.getName());
        Collections.addAll(args, paramsFile.toString());

        // Print commandline debug message
        LogHelper.debug("Commandline: " + args);

        // Build client process
        LogHelper.debug("Launching client instance");
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.environment().put("CLASSPATH",classPathString.toString());
        builder.directory(params.clientDir.toFile());
        builder.inheritIO();
        if (pipeOutput) {
            builder.redirectErrorStream(true);
            builder.redirectOutput(Redirect.PIPE);
        }
        // Let's rock!
        Process process = builder.start();
        return process;
    }

    @LauncherAPI
    public static void main(String... args) throws Throwable {
        if(JVMHelper.OS_TYPE == OS.MUSTDIE)
        {
            AvanguardStarter.main(args);
        }
        JVMHelper.verifySystemProperties(ClientLauncher.class, true);
        LogHelper.printVersion("Client Launcher");

        // Resolve params file
        VerifyHelper.verifyInt(args.length, l -> l >= 1, "Missing args: <paramsFile>");
        Path paramsFile = IOHelper.toPath(args[0]);
        // Read and delete params file
        LogHelper.debug("Reading ClientLauncher params");
        Params params;
        ClientProfile profile;
        HashedDir assetHDir, clientHDir;
        RSAPublicKey publicKey = Launcher.getConfig().publicKey;
        try (HInput input = new HInput(IOHelper.newInput(paramsFile))) {
            params = new Params(input);
            profile = new ClientProfile(input, true);

            // Read hdirs
            assetHDir = new HashedDir(input);
            clientHDir = new HashedDir(input);
        } finally {
            Files.delete(paramsFile);
        }

        // Verify ClientLauncher sign and classpath
        LogHelper.debug("Verifying ClientLauncher sign and classpath");
        String[] classpath = JVMHelper.getClassPath();
        LinkedList<Path> classPath = resolveClassPathList(params.clientDir, profile.getClassPath());
        int counter = classPath.size();
        for (String classpathURL : classpath) {
            Path file = Paths.get(classpathURL);
            if (!file.startsWith(IOHelper.JVM_DIR)) {
                for (Path classPathURL : classPath)
                {
                    if(classpathURL.equals(classPathURL.toString())) {
                        counter--;
                        break;
                    }
                }
            }
        }
        if(counter != 0)
        {
            throw new SecurityException(String.format("Forbidden classpath entry, %d != 0",counter));
        }
        URL[] classpathurls = resolveClassPath(params.clientDir, profile.getClassPath());
        classLoader = new LauncherClassLoader(classpathurls,ClassLoader.getSystemClassLoader());
        Thread.currentThread().setContextClassLoader(classLoader);
        // Start client with WatchService monitoring
        boolean digest = !profile.isUpdateFastCheck();
        LogHelper.debug("Starting JVM and client WatchService");
        FileNameMatcher assetMatcher = profile.getAssetUpdateMatcher();
        FileNameMatcher clientMatcher = profile.getClientUpdateMatcher();
        try (DirWatcher assetWatcher = new DirWatcher(params.assetDir, assetHDir, assetMatcher, digest);
            DirWatcher clientWatcher = new DirWatcher(params.clientDir, clientHDir, clientMatcher, digest)) {
            // Verify current state of all dirs
            //verifyHDir(IOHelper.JVM_DIR, jvmHDir.object, null, digest);
            verifyHDir(params.assetDir, assetHDir, assetMatcher, digest);
            verifyHDir(params.clientDir, clientHDir, clientMatcher, digest);

            // Start WatchService, and only then client
            CommonHelper.newThread("Asset Directory Watcher", true, assetWatcher).start();
            CommonHelper.newThread("Client Directory Watcher", true, clientWatcher).start();
            launch(profile, params);
        }
    }

    @LauncherAPI
    public static void verifyHDir(Path dir, HashedDir hdir, FileNameMatcher matcher, boolean digest) throws IOException {
        if (matcher != null) {
            matcher = matcher.verifyOnly();
        }

        // Hash directory and compare (ignore update-only matcher entries, it will break offline-mode)
        HashedDir currentHDir = new HashedDir(dir, matcher, false, digest);
        if (!hdir.diff(currentHDir, matcher).isSame()) {
            throw new SecurityException(String.format("Forbidden modification: '%s'", IOHelper.getFileName(dir)));
        }
    }

    private static void addClientArgs(Collection<String> args, ClientProfile profile, Params params) {
        PlayerProfile pp = params.pp;

        // Add version-dependent args
        Version version = profile.getVersion();
        Collections.addAll(args, "--username", pp.username);
        if (version.compareTo(Version.MC172) >= 0) {
            Collections.addAll(args, "--uuid", Launcher.toHash(pp.uuid));
            Collections.addAll(args, "--accessToken", params.accessToken);

            // Add 1.7.10+ args (user properties, asset index)
            if (version.compareTo(Version.MC1710) >= 0) {
                // Add user properties
                Collections.addAll(args, "--userType", "mojang");
                JsonObject properties = Json.object();
                if (pp.skin != null) {
                    properties.add(SKIN_URL_PROPERTY, Json.array(pp.skin.url));
                    properties.add(SKIN_DIGEST_PROPERTY, Json.array(SecurityHelper.toHex(pp.skin.digest)));
                }
                if (pp.cloak != null) {
                    properties.add(CLOAK_URL_PROPERTY, Json.array(pp.cloak.url));
                    properties.add(CLOAK_DIGEST_PROPERTY, Json.array(SecurityHelper.toHex(pp.cloak.digest)));
                }
                Collections.addAll(args, "--userProperties", properties.toString(WriterConfig.MINIMAL));

                // Add asset index
                Collections.addAll(args, "--assetIndex", profile.getAssetIndex());
            }
        } else {
            Collections.addAll(args, "--session", params.accessToken);
        }

        // Add version and dirs args
        Collections.addAll(args, "--version", profile.getVersion().name);
        Collections.addAll(args, "--gameDir", params.clientDir.toString());
        Collections.addAll(args, "--assetsDir", params.assetDir.toString());
        Collections.addAll(args, "--resourcePackDir", params.clientDir.resolve(RESOURCEPACKS_DIR).toString());
        if (version.compareTo(Version.MC194) >= 0) { // Just to show it in debug screen
            Collections.addAll(args, "--versionType", "Launcher v" + Launcher.VERSION);
        }

        // Add server args
        if (params.autoEnter) {
            Collections.addAll(args, "--server", profile.getServerAddress());
            Collections.addAll(args, "--port", Integer.toString(profile.getServerPort()));
        }

        // Add window size args
        if (params.fullScreen) {
            Collections.addAll(args, "--fullscreen", Boolean.toString(true));
        }
        if (params.width > 0 && params.height > 0) {
            Collections.addAll(args, "--width", Integer.toString(params.width));
            Collections.addAll(args, "--height", Integer.toString(params.height));
        }
    }

    private static void addClientLegacyArgs(Collection<String> args, ClientProfile profile, Params params) {
        args.add(params.pp.username);
        args.add(params.accessToken);

        // Add args for tweaker
        Collections.addAll(args, "--version", profile.getVersion().name);
        Collections.addAll(args, "--gameDir", params.clientDir.toString());
        Collections.addAll(args, "--assetsDir", params.assetDir.toString());
    }

    private static void launch(ClientProfile profile, Params params) throws Throwable {
        // Add natives path
        //JVMHelper.addNativePath(params.clientDir.resolve(NATIVES_DIR));

        // Add client args
        Collection<String> args = new LinkedList<>();
        if (profile.getVersion().compareTo(Version.MC164) >= 0) {
            addClientArgs(args, profile, params);
        } else {
            addClientLegacyArgs(args, profile, params);
        }
        Collections.addAll(args, profile.getClientArgs());
        LogHelper.debug("Args: " + args);
        // Resolve main class and method
        Class<?> mainClass = classLoader.loadClass(profile.getMainClass());
        MethodHandle mainMethod = MethodHandles.publicLookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class));
        // Invoke main method with exception wrapping
        LAUNCHED.set(true);
        JVMHelper.fullGC();
        System.setProperty("minecraft.applet.TargetDirectory", params.clientDir.toString()); // For 1.5.2
        mainMethod.invoke((Object) args.toArray(EMPTY_ARRAY));
    }

    private static URL[] resolveClassPath(Path clientDir, String... classPath) throws IOException {
        Collection<Path> result = new LinkedList<>();
        for (String classPathEntry : classPath) {
            Path path = clientDir.resolve(IOHelper.toPath(classPathEntry));
            if (IOHelper.isDir(path)) { // Recursive walking and adding
                IOHelper.walk(path, new ClassPathFileVisitor(result), false);
                continue;
            }
            result.add(path);
        }
        return result.stream().map(IOHelper::toURL).toArray(URL[]::new);
    }
    private static LinkedList<Path> resolveClassPathList(Path clientDir, String... classPath) throws IOException {
        Collection<Path> result = new LinkedList<>();
        for (String classPathEntry : classPath) {
            Path path = clientDir.resolve(IOHelper.toPath(classPathEntry));
            if (IOHelper.isDir(path)) { // Recursive walking and adding
                IOHelper.walk(path, new ClassPathFileVisitor(result), false);
                continue;
            }
            result.add(path);
        }
        return (LinkedList<Path>) result;
    }

    public static final class Params extends StreamObject {
        // Client paths
        @LauncherAPI public final Path assetDir;
        @LauncherAPI public final Path clientDir;

        // Client params
        @LauncherAPI public final PlayerProfile pp;
        @LauncherAPI public final String accessToken;
        @LauncherAPI public final boolean autoEnter;
        @LauncherAPI public final boolean fullScreen;
        @LauncherAPI public final int ram;
        @LauncherAPI public final int width;
        @LauncherAPI public final int height;

        @LauncherAPI
        public Params(Path assetDir, Path clientDir, PlayerProfile pp, String accessToken,
            boolean autoEnter, boolean fullScreen, int ram, int width, int height) {

            // Client paths
            this.assetDir = assetDir;
            this.clientDir = clientDir;

            // Client params
            this.pp = pp;
            this.accessToken = SecurityHelper.verifyToken(accessToken);
            this.autoEnter = autoEnter;
            this.fullScreen = fullScreen;
            this.ram = ram;
            this.width = width;
            this.height = height;
        }

        @LauncherAPI
        public Params(HInput input) throws IOException {

            // Client paths
            assetDir = IOHelper.toPath(input.readString(0));
            clientDir = IOHelper.toPath(input.readString(0));

            // Client params
            pp = new PlayerProfile(input);
            accessToken = SecurityHelper.verifyToken(input.readASCII(-SecurityHelper.TOKEN_STRING_LENGTH));
            autoEnter = input.readBoolean();
            fullScreen = input.readBoolean();
            ram = input.readVarInt();
            width = input.readVarInt();
            height = input.readVarInt();
        }

        @Override
        public void write(HOutput output) throws IOException {

            // Client paths
            output.writeString(assetDir.toString(), 0);
            output.writeString(clientDir.toString(), 0);

            // Client params
            pp.write(output);
            output.writeASCII(accessToken, -SecurityHelper.TOKEN_STRING_LENGTH);
            output.writeBoolean(autoEnter);
            output.writeBoolean(fullScreen);
            output.writeVarInt(ram);
            output.writeVarInt(width);
            output.writeVarInt(height);
        }
    }

    private static final class ClassPathFileVisitor extends SimpleFileVisitor<Path> {
        private final Collection<Path> result;

        private ClassPathFileVisitor(Collection<Path> result) {
            this.result = result;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (IOHelper.hasExtension(file, "jar") || IOHelper.hasExtension(file, "zip")) {
                result.add(file);
            }
            return super.visitFile(file, attrs);
        }
    }
}

// It's here since first commit, there's no any reasons to remove :D
// ++oyyysssssssssssooooooo++++++++/////:::::-------------................----:::----::+osssso+///+++++ooys/:/+ssssyyssyooooooo+++////:::::::::-::///++ossyhdddddddhhys/----::::::::::::::/:////////////
// ++oyyssssssssssoooooooo++++++++//////:::::--------------------------------:::::-:::/+oo+//://+oo+//syysssssyyyyyhyyyssssssoo+++///::--:----:--:://++osyyhdddmddmdhys/------:::::::::::::::///////////
// ++syyssssssssssoooooooo++++++++///////:::::::::::::::-----------------------::--::/++++/:--::/+++//osysshhhhyhhdyyyyyssyssoo++//::-------------::/+++oyhyhdddmddmdhy+--------::::::::::::::://///////
// ++ssssssssssssooooooooo++++++///////::::::::-------------------------------------::/+//:----://+//+oyhhhddhhdhhhyhyyyyhysoo+//::----------------://++oshhhhmddmddmhhs:---------:::::::::::::::///////
// ++sssssssssssooooooooo++++////::::::::::------------------------------------:---:::///:-----://++osyhddddhdddhhhhhyyhdysso+/::-------------------://+ooyhhddmddmdmdhy/----------::::::::::::::://////
// ++sssssssssoooooooo+++////::::::::::-------------------------------------::///::::///::--::/++osyhhddmmdhddddhhhhhyhdhyso+/:----------------------::/++oyhdhdmmmmmddho-------------::::::::::::://///
// /+ssssssssosooo+++/////::::::::::----------------------------------:::::://+++ooossso////++oosyhddddmmdhddddhhhhhhhdhyddhs+:-----------------------::/++syhddmmmmdddho--------------::::::::::::::///
// /+sssssssooo+++//////::::::::::-------------------------------:::://++++ooooooooooooo+/+oosyyhhddddmmddddddhhhhhhhdhyyssyyhs+::-------------::://++/+/o++shddddmmmddh+----------------::::::::::::://
// /+sssooso++++/////::::::::::::------------------------::::::///++o++//:--...............-/osyyhddmmmmddddhhhhhyyhdhhdmddhysoso::--------::/osyyyssyyssssoosdmddddddy+:-------------------:::::::::::/
// /+sssooso+++////::::::::::::--------------------:::::////++o+//:-......................--+ossydddmmdmmdddhhhhyyhddyyydmhhooo++/:-------::/+oshhhyso+/://++ohmmmdhhy+:---------------------:::::::::::
// /+sooooso++/////:::::::::::--------------:::::///+++oo+//:-............................-/+oyydddmmddddddhhhyyyhhhyysosss+:/+///:--.---://+o/ohhdhoyyo+/://+ymmdhsyo:------------------------:::::::::
// /+ooooooso+////:::::::::------------:::::///++oooo/:-..................................:/oyyyhdmdddddddhhhhyyhyss++///:::/::::/:-...--::://-/shy+-:+s+/::/+hdhysos/--------------------------::::::::
// /+oooooooo++///::::::::::-:----:::::::///+oosyy/-.....................................-/+syhhddddddddhhhhhhhysso+/::::-----:::::-..------:::::////:::::::/sdysso+o:..----.--------------------:::::::
// /+ooooooooo++///:::::::::::::::::::///++osyhmmdo:-...................................-/+syyhdddddddddhdddmmhys+/::----------:::--..---------::::::-----::+dhsoso++:....--.--..----------------:::::::
// /ooooooooooo++///::::::://///++oooosssyydddhdhs+so/+++:-............................./+syyhdddddddddddddmmmhs+//:------------:--..---------------------:/hdsooso+/-....--.....------------------:::::
// /oooooooo++++o++//:::::::///++ossso+ooosysos++o/so//////--..........................:yhddhdddmmdddmmmdddmddhs+/::----------:::---.--------------------:/hdhsooso+/-.....-........----------------::::
// /ooooooo+++++++++//:::::::::/+o+ooooyyso+oo/++o/oo///://////::--...................-sdhddhddmmdhdmmmddhhdmNds+/:::--------:/:------------------------::sdhyooyso+:-...............---------------::::
// /ooooooo+++++++++++//:::://+++++ssyys+/+y+///++/so///:://://::::/---://............-smdddddmmhyhhdddhhsydmNmyo//:::------://:--------:::------------::odhysshyo++-................---------------::::
// /oooooo+++++++++++/+o+/++++++++ohhoo+//yo//:/+//so:/:////::+////+///+so/++/:--------:hdddmdhyssyhhddhsosdmNmho+/:::------////:------::::-----------::+hdhhhyyo+o/-................----------------:::
// /ooo++o++++++++++//+oooooo++++oyyoo+//os+////+//so/://::////+///+//+ssooo+++++++/++//odhhhyoooosyhhhysoodmmNds+//:::-----:++o+/:::/+//:----------::/+ydhyyyyo+oo:...................--------------:::
// /oooo+++++++++++/////+ossoooooss++////+so/::++//os://:/::://////++/os+++++++/++++++++osyso++++osyyyyssoodmmmmyo+/::::-----::/++++////:--------::::/+shhysoso+ss/.....................------------:::/
// +ooo++++++++++/////////+oooosso////:::+oso/:/+//oy+//:///://////+//o+/++////////++++ooooo++++ossyhhsoosodmmmmds+//////::::::::::::---------::::://+shhyso+++ss:........................----------:://
// +ooo++++++++++///////////////++++//:::/++o////+/+yo//////////////:/o:/+//////////+oooo+oooo++ooyyyyysssshmmmmmds+//////+++++///:::::::::::::::://+syhhso+++oo/..........................---------::/+
// +o+o+++++++++///////////////::///++//:::/+///////oy+///://///////-++:+/://://::/++so++ooooooossysyyhhssyhmmmmNmds+//:::/++++oooooooo+++//:::::/+oosyys++oso+/-..........................--------:://+
// +oo+++++++++/////////////////:::://++//////:/+/:/+ss+//////++///:://///::://:://++o++ossssyyssosyyhhyssydmmmmNNmmyo//////++///////////::::::/+oooosossssyyo/-...........................--------://++
// +++++++++++//////////////::/::::::::///++/:///:://oss//:////++//::+/////:::/:://+so++ssyyhhhoosyyyysoosydmmmNNmmmmho+//::////+++++///:::::/+osssssshmdssyyo-............................-------::/+++
// +oo++++++++++/////////////:/::::::::::://+/////:/:+oso+:////++/://+++++++/::///+oooosyyyhhhsossssso+osshmmmmNmmmmmdys+/::::---:::::::::/+osyyyssyddmmhsyhy+-.............................-----:://+++
// +o++++++++++///////////:/::::::::::::---::/+++/::::+oso/::/://:://++++o+:::+//++ssooyyyhhyyssssoo++sssymmmmNmmmmmmhysso/:::::-----:::/+yhhhyyyhddmmdhhyhys+:-............................-----://+++/
// ++++++++++++/////////////::::::::----------://////:/+oso+/:::::://+++o+//+ooooo+++oshyhhhysssso++ososhmNNNNNmmmmhhyoooooo+/////://+oyhmmdhhyhdddddhyyysssoo+:--.........................-----://++//:
// o++++++++++/////////////:::::::::-------------:////::/ooo/:::::///+os++ssyyyyssssssyhhhysssssoooooosdmNNNNNmmmdhsss+++ooooooossyyhdmmmmdhyysyhhyssooooossooys+//:--.....................----::/+++/::
// o+++++++++//////////////::::::::----------.....-://////+oo+:://://ooooyhhhhdddhhhyhhhhyssssssssooshmNNNNNNNmmmdooo++//++oooossshddmmmddys++ooo+/+++ossyhhyyyyysoo+//:--.................----://++/:::
// ++++++++++//////////////:::::::--------..........-::///:/+++::::/+o+/ohhhhhhyyyyyyhhhysyyysyyyyhdmNNNNNNNNmmmhso++//:///+ooossydddmmmddhyysso+/++ossshddddddhyyso++++////::::::--.......---://++/:-::
// o+++++++++///////////////:::::::------..............-:////++/:///+osoyhhhhyssssyhhdddhhhyyhhdmmmmmmmmmmmmmmmho+oo+/::::///+++oyhhdmmmddhhysoo+oosyhhdmmmddddhhyssoo+++++++++++///:-....---://++//-:::
// +++++++++//+///////////:/::::::------.................-://////////+ossyyyyyhhhhddddhhhhddmmmmddddmmmmmmmmmdyo+++os/::::::://+ydhhmmmddddddddddmmmmmmmmmmmmddhyyysoo+o+++//++////::::-----::/++//--::+
// +++++++++///////////////:::::::------....................-:///++/::+syyyhhhhhhhhhhhhddmmmddddddddmmmmmmmmdso++++++++/:::://ohdhydmmddddhdmmmmmmhhdmmmmmmmmmhhyyyysooso+/+////////////:::::/+++/:-::++
// ++++++++++//////////////:/:::::------......................-:/+/:::+shhdhhhhhhhhhdddmmmddddhhhhhddmmmmmmdyo+++++/+/++++++oydmdhyhdddhhsssydmdmmddddddmmmmmdhhsyysoso++++///+//////://+//////+/:-::/o:
// ++++++++++/////////////://:::::------.........................::::/osdmmmmmddddddddddhhysssooosyhdmmmmmdsooo+o++/+++++++shmmddhyyhmhso/+shhdmddhhhdmmmmddhhyyysoooo//+++/://///:/+//+///////::-::/o/:
// +++++++++++////////////:/::::::-----..........................-:://+oymmmmmddddhhhhhhhhyysoo+osyhhdmdhyoo/++so++++oooooohmmmmdhhyhdh+//+ydhyssshddmmdhhhyyssso+/+o+//+////:////+//+////+////+++////:-
// +++++++++//////////////://:::::-----.........................-::::///oyddddhhhhhdmdddhddhhysssyhhdmdyo++o/+/+oooo+++o+osdmmmmdhhyhdd+/+syo++oshddhyyhhyssooo+///+/++/+++/://:/+/++++o++oooooso++oo++/
// o+++++++++///////////////::::::-----.........................::::::++o+shmdyhhsyhdddmmddddhsosyhhmmh++/+o+/+/++++ooososhmddddhyyso++oosso++osyyhhddmdysooo+//+/o+++/+/o//o+/++++/+ooosssooooo+++++/++
// o+++++++++///////////////:::::------........................-::::::+ossyyhyo+++shddmmdyyhyo//osysdds//++++o+/++o+o++oosdhdddhhyyysoooysoosyyyhdddhmmss+o+o++//+/+++//+++++oo++/oossssssssoooooooo++++
// o++++++++++/////////////:/::::-----.........................:::::/oyysyysooo+oyhdddmdhhddh+++osyhdh//////++o+///++ooooyshdmmmmdddhdhssshhysydmmysshyoo++++++/+++////+o+ooso+++ssyyyyyyyyysssooooo+o++
// ooo+++++++++//////////////::::-----.........................:-:::ohdhhhhysssssyhhhyhhhdmmd+++oyddho////:/++++++/+/+++oshmmmmmdhyyyssyhdysshmmmdhyysoo+/+o+//++/+//++ososso++syhyyyhyyyyyyyysssysssyss
// ooo+++++++++////////////::::::------.......................---::+hddhhyysoosoooosssooosyyyssyhdhho+///////////+/+++++symmmdys+//+oyhhyssyhdmmmmmhyo+++++++/+++++oo++ohssoooyhhhhhhhyyyhyhhyyhyyhyyyyy
// ooo++++++/++/////////////::::::-----.......................:::/:/+sssyyysssyo++oosyhhhhyyssoo+oooo+///////////+++++oyhddhysssyyhdddhysoshmNNNNmmyoo+++++++/+/++++o+osdyssoyhhhhhhhhhhhhhhyhhhhhhhhhhh
// oooo++++++++/////////////::::::-----.......................:::::///++ooooo++/:/+syhhddys++/:/+++o+++////++////+++osysydhydmddddyshysoshmmmmmNNmyooo+oo+++++/++oooooshdyoyyhhhhhhhhhhdhhhhhdhhhhdhhhhd
// ooo+++++++++/////////////::::::----.......................-://::::://////:///://ooooo+////////++oo++++////++//oosyhyyyyshddmmhyhyysyhmNmdddmNdysooooosooooooooooso+shdysyhdhhhhhhhdddhdhdddddddhhhddd
// oooo+++++++++////////////::::::----.......................::/:::::///////://::::::::::://:/::/++oo+++////++++oohyhhhhhhsydmdhhhyyhhyymmmdddmmyssoooosysossosoo+ssossddysydhhddhhddhhddddddddddddhdddd
// oooo+++++++++/++//////////::::-----......................-:::::::///////::/:::::::::::::::::/:/++oo++++++/++ssyhhddddhyyohmddhhddhyhhmmmddmmdsssoosssosssossooossoyhmhyyhdhddddhhhdhhddddddddhddddmdd
// oooo+++++++++//+//////////::::----.......................-:::::///:/::::::/::::-::::/:-:::::///++ooooo+++++shhdddddhhyyhysdddhdmdhhddmdhhydmhssosssysssssssoosssssyhmysyddddddhdhdddddddddddddddmdddd
// ooooo+++++++++++//////////::::----.......................::::::://://:::://::-:::-::::::::::////++oosoo+o+oyhhdddddhhhhddyyhyhddhhhdmmmhyyddhssssssyyysssssssssysyyddyyhddddddddhhhhhddddddddmddddddm
// ooooo+o++++++++++/////////::::----.......................::::::/:///::::::::::::::::::::/:::::///++osssososssyhddddhhhhdddssoossyhdmmmmhyyhhhyssyyyyysyyyssyssssshhmhyhdddddddddddhdhhddmddmdddddhdmm
// ooooooo++++++++++/////////:::-----......................-:::::/::::/:/::://::::/::::::::::::::/:://+ooosssyysyyyyyyyhddmmysssssyyhhddmdhysyhhyyyyyyyysyyyyysssyysyydhydddddddhddddhddddddddddddhddmmm
// ooooooo+++++++++++////////:::-----......................:::::/:::://:-::/:://::/::::-:::::::::/:/::/++oosssyyysssssyhdddhsyysssyyhhddhhyssyyyyyyyyysyyssyyyyyyyyyhhdyhdddhddddhddddddmmddddddhdddmmmm
// ooooooo+o+++++++++++//////:::-----.....................-::::::/:///:::::::::/::/::::::::::/::/:///::////+oosyysssyssyyyysssyhyyhddmmdyysoossyssyyysssyyssysyyyyshyddhddhhhddhdddddddddmddhdddhhdhhdmm
// ooooooooo++++++++++///////:::-----.....................-::::////:::::::::::::::::::://::::/-:::::/:://::///+osyyssosossssssysyhdddddhysoosssyyssssyssyyyyyysssyshddddddhddddddddddmddddhdhhhddddmmmmm
// oooooooooo+o++++++++++////::::----.....................::://::::/::::::::::::::/:://::::::::::/::::::///////++ooooosoo+ososssshddhhysssooossssssssyssysyyyyyysysyhddddddddddddddddddddhddhhhddddmmmmd
// soooooooooo+++++++++++////::::----....................-:::::////:::/:::::::/::://///:/::::::::/:///://:/://+//+++++++oo+ooooooshdyyyssossoosssyssyysssssyyyyysssshdddddddddddddddddddhhhdhhhddmmmmdmd
// sssooooooooo++++++++++////::::----....................-:::/:://///:::::::::::::/://:/::://::::::://:::/://///++/+++/+++o+oosssyyysssyysosoososysssyysyyyyhysyssyyhdddddddddddddddddhddhddhddmmmmmdmmm
// ssssoooooooo++++++++++////:::::----...................:::::://::::::::::::-::://///:////::/:/:::://///:://://+o++++++oooosoosoossoososssooososssssyyyyyssyyyssyyyhmmdddhhhhhhhddhdddddddddmmmmmmddmmm
// sssoooooooooooo+++++++/////::::----...................:://///:/:::::::::::::::://///////////+/:/://://::////+++++++ooossoossososososssoososossssssyssyyyyyyyyyysyhdhhhhhhhhhhhdhhhhhdddddmmmmmmddmmNN
// ssssoooooooooooo+++++++////::::----..................-::::/:/:::::::::::::://:::://///:/:///////////:://+/++++++oooooooosssssoosssssssosooosooossssyyysyyysyyyysoyyooossssyyhhhddhddhhhhhddmmmmmmmmmm
// ssssssoooooooooooo++++++///:::------.................::::::://:::::::-:::::/::://////:///////////////////++o++oo+ooooosoossossosssossssoosoosoosssyysyyyysyyssysoyo+oooo//+osossosyhhhhhhddmmddmmmmdm
// sssssssosooooooooooo++++///::::-----................./::::/:/:::::::::-::/:::/://///////////+//++/++//+oo+oo++ooo+ooooosssosoossssysosssssooooossysyyyyysyyyyyssoo++ooo+//+s/:---::/+oosyyyhhhhdmmmmm
// yssssssssssoooooooo+++++///::::-----................://::::::/:/::::::-::/::::://///////////////+/++++++oo+++oooooo+oooosssossosysosssosssoooosssyyyyyyyyyyyysso++++++//:/o/:--::::::::::/++oossymmmm
// yysssssssssssooooooo++++////:::------..............-///:::/:::/:::/::::://:::::::///////////////++++++o++++o++oo+ooooooooosysosssssoosssssosoossyyyyyssyyyyysss++oooo+///oo/::::-::::::::::::////sdmm
// yyysssssssssssooooooo++++///:::-------.............:/:::/:/:::::::::://://::/::::/:////////+/////++++++oo+ooo++oooo+oooossssssssssoossoooooossssyyyyyyyyyyyysso++ooo/--:/:.::::..::::-..-::://////ohm
// yyyssssssssssssoooooo++++///::::------............-/:::/://///:::::::::://::/::::::////:///+/+++/++++++/++++++o+o++oooooooosssssoossssssssosssssyyyyyyyyyyyyso++ooo:./.-+-.---...--:::.--::::://////o
// yyyysyssssssssssoooooo+++///::::--------.........-/:::::////::/::::::::::/:://:/:::///////////+/+++++//++++++++oo+++ooosoooossssssossoosssssssssyyyyyyyyyyysoo+oooo`:+.+.--.--.-::.-.----::-:.///////
// yyyyyyyyyssssssssooooo+++///:::::----------......:///:::://///:::::::::::://:/:::://///////+////++++////+++++++++o++oooosssososossossososssssysyyysyyyyyyysso+oooo+--:-+-.-.-------::----:--:-://////
// yyyyyyyyyyssssssssoooo++++//:::::-------------.--////://::/:::::/::::/:::://:/::::/:/:///////+//+++///+//++/++++o++++ooooooosssossssosoossssssssssyyyyyyyyso++ooso++/o+/::::::::::::::://////////////
// yyyyyyyyyyyssssssssoooo+++///::::---------------:////////////:/:///::::://////::::::://///////+/++////+//+/++++++++ooosooooooososoosososssosyyyssyysyyyyysso+oso+//+oo/:::::::::::::/:::///////////++
// yyyyyyyyyyyyysssssssooo+++///:::::---------------+////:///:///:::::///::://///:::/:::///////////++/++++///++++/++o+oooooooooosoososoossssssssysssyyyyyyysoo+ooso+++ss/::::::::::::::://///////////+++
// hhyyyyyyyyyyyyyssssssooo++///:::::---------------/////////:////://:///::://///://::://////////++////+///+///++o++++ooooooooooooooooooooososososssyssssysooo+oso+++os+:::::///////////////////////++++
// hhhyyyyyyyyyyyyyyssssooo+++///::::---------------:////////////:////////:://///::://////////++/+++//////+++++++++++++++oo+++o+ooososooosssoossssssssssssooo+osso+oos+:::////////////////////////++++++
// hhhhhhyyyyyyyyyyyyssssoo+++////:::::--------------:///////////:///://///:///////:///://///++/++++//++/++++/++++++++++++++o+oooooooosoosssssoosssssssssso++ossooooyo/:///////////////////////++++++++o
