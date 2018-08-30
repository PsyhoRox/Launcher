package launcher;

import launcher.helper.LogHelper;
import launcher.transformers.SystemClassLoaderTransformer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.jar.JarFile;

import javassist.bytecode.ClassFile;

@LauncherAPI
public class LauncherAgent {
	private static final boolean enabled = false;
    public static Instrumentation inst;
    
    public static void premain(String agentArgument, Instrumentation instrumentation) {
        System.out.println("Launcher Agent");
        inst = instrumentation;

        if(ClassFile.MAJOR_VERSION > ClassFile.JAVA_8 || enabled) {
	        	inst.addTransformer(new SystemClassLoaderTransformer());
	        Class<?>[] classes = inst.getAllLoadedClasses(); // Получаем список уже загруженных классов, которые могут быть изменены. Классы, которые ещё не загружены, будут изменены при загрузке
	        ArrayList<Class<?>> classList = new ArrayList<>();
	        for (int i = 0; i < classes.length; i++) {
	        	if (inst.isModifiableClass(classes[i])) { // Если класс можно изменить, добавляем его в список
	        		classList.add(classes[i]);
	        	}
	        }
	        // Reload classes, if possible.
	        Class<?>[] workaround = new Class[classList.size()];
	        try {
	        	inst.retransformClasses(classList.toArray(workaround)); // Запускаем процесс трансформации
	        } catch (UnmodifiableClassException e) {
	        	System.err.println("MainClass was unable to retransform early loaded classes: " + e);
	        }
        }
    }

    public static void addJVMClassPath(String path) throws IOException {
        LogHelper.debug("Launcher Agent addJVMClassPath");
        inst.appendToSystemClassLoaderSearch(new JarFile(path));
    }
    
    public static long getObjSize(Object obj) {
    	return inst.getObjectSize(obj);
    }
}
