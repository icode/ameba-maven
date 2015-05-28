package ameba.maven;

import ameba.core.Application;
import ameba.db.OrmFeature;
import ameba.db.ebean.EbeanFinder;
import ameba.db.ebean.EbeanPersister;
import ameba.db.ebean.EbeanUpdater;
import ameba.dev.Enhancing;
import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.classloading.enhancers.Enhancer;
import ameba.dev.classloading.enhancers.EnhancingException;
import ameba.util.ClassUtils;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.collect.Sets;
import javassist.ClassPool;
import javassist.CtClass;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @goal enhance
 * @requiresProject true
 * @requiresDependencyResolution compile
 * @phase process-classes
 * @execute phase="process-classes"
 */
public class AmebaMojo extends AbstractMojo {
    private final static String JAVA_PKG = "src/main/java";
    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     */
    private MavenProject project;
    /**
     * Set the destination directory where we will put the transformed classes.
     * <p>
     * This is commonly the same as the classSource directory.
     * </p>
     *
     * @parameter
     */
    private String classDestination;
    /**
     * Set the directory holding the class files we want to transform.
     *
     * @parameter default-value="${project.build.outputDirectory}"
     */
    private String classSource;
    /**
     * Set the application config ids.
     *
     * @parameter
     */
    private String[] ids;
    private ReloadClassLoader classLoader;

    public void execute()
            throws MojoExecutionException {
        final Log log = getLog();
        if (classSource == null) {
            classSource = "target/classes";
        }

        if (classDestination == null) {
            classDestination = classSource;
        }
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.setLevel(Level.OFF);

        log.info("Enhancing classes ...");

        File f = new File("");
        log.info("Current Directory: " + f.getAbsolutePath());
        MavenApp app = new MavenApp();
        app.setSourceRoot(f.getAbsoluteFile());
        app.setPackageRoot(new File(JAVA_PKG).getAbsoluteFile());
        ClassLoader oldClassLoader = ClassUtils.getContextClassLoader();
        classLoader = buildClassLoader(app);
        Thread.currentThread().setContextClassLoader(classLoader);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        Properties properties = Application.readDefaultConfig(config);
        Application.readAppConfig(properties, Application.DEFAULT_APP_CONF);
        if (ids != null) {
            Set<String> configFiles = Application.parseIds2ConfigFile(ids);
            for (String conf : configFiles) {
                Application.readAppConfig(properties, conf);
            }
        }

        //todo 更改为配置的，不是固定class
        OrmFeature.setFinderClass(EbeanFinder.class);
        OrmFeature.setPersisterClass(EbeanPersister.class);
        OrmFeature.setUpdaterClass(EbeanUpdater.class);

        Application.readModuleConfig(config, false);

        Enhancing.loadEnhancers(config);

        process("", true);
        Thread.currentThread().setContextClassLoader(oldClassLoader);
    }

    private void process(String dir, boolean recurse) {

        String dirPath = classSource + "/" + dir;
        File d = new File(dirPath);
        if (!d.exists()) {
            File currentDir = new File(".");
            String m = "File not found " + dirPath + "  currentDir:" + currentDir.getAbsolutePath();
            throw new RuntimeException(m);
        }

        File[] files = d.listFiles();
        File f = null;
        try {
            for (File file : files) {
                f = file;
                if (file.isDirectory()) {
                    if (recurse) {
                        String subdir = dir + "/" + file.getName();
                        process(subdir, recurse);
                    }
                } else {
                    String fileName = file.getName();
                    if (fileName.endsWith(".java")) {
                        // possibly a common mistake... mixing .java and .class
                        getLog().debug("Expecting a .class file but got " + fileName + " ... ignoring");

                    } else if (fileName.endsWith(".class")) {
                        String name = file.getPath().replace(classSource, "");
                        transformFile(name);
                    }
                }
            }

        } catch (Exception e) {
            String fileName = f == null ? "null" : f.getName();
            String m = "Error transforming file " + fileName;
            throw new RuntimeException(m, e);
        }

    }

    private void transformFile(String file) {
        if (file.startsWith("/")) {
            file = file.substring(1);
        }
        String name = file.replace("/", ".");
        name = name.substring(0, name.length() - 6);
        ClassDescription desc = classLoader.getClassCache().get(name);
        enhance(desc);
    }

    private void enhance(ClassDescription desc) {
        if (desc == null) return;
        ClassPool classPool = Enhancer.getClassPool();
        CtClass clazz;
        try {
            clazz = classPool.makeClass(desc.getEnhancedByteCodeStream());
        } catch (IOException e) {
            throw new EnhancingException(e);
        }
        if (clazz.isInterface()
                || clazz.getName().endsWith(".package")
                || clazz.isEnum()
                || clazz.isFrozen()
                || clazz.isPrimitive()
                || clazz.isAnnotation()
                || clazz.isArray()) {
            return;
        }
        for (Enhancer enhancer : Enhancing.getEnhancers()) {
            enhance(enhancer, desc);
            try {
                FileUtils.writeByteArrayToFile(desc.classFile.getAbsoluteFile(), desc.enhancedByteCode);
            } catch (IOException e) {
                getLog().error(e);
            }
        }
    }

    private void enhance(Enhancer enhancer, ClassDescription desc) {
        try {
            long start = System.currentTimeMillis();
            enhancer.enhance(desc);
            getLog().debug(
                    System.currentTimeMillis() - start + "ms to apply "
                            + enhancer.getClass().getSimpleName()
                            + "[version: " + enhancer.getVersion() + "] to " + desc.className);
        } catch (Exception e) {
            throw new EnhancingException("While applying " + enhancer + " on " + desc.className, e);
        }
    }

    private ReloadClassLoader buildClassLoader(Application app) {

        URL[] urls = buildClassPath();
        ClassLoader loader = URLClassLoader.newInstance(urls, Thread.currentThread().getContextClassLoader());
        ReloadClassLoader classLoader = new MojoClassLoader(loader, app);
        app.setClassLoader(classLoader);
        return classLoader;
    }

    private URL[] buildClassPath() {
        try {
            Set<URL> urls = Sets.newLinkedHashSet();

            URL projectOut = new File(project.getBuild().getOutputDirectory()).toURI().toURL();
            urls.add(projectOut);

            Set<Artifact> artifacts = project.getArtifacts();

            for (Artifact a : artifacts) {
                urls.add(a.getFile().toURI().toURL());
            }

            getLog().info("ClassPath URLs: " + urls);

            return urls.toArray(new URL[urls.size()]);

        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static class MavenApp extends Application {
        public MavenApp() {
        }
    }

    private class MojoClassLoader extends ReloadClassLoader {

        public MojoClassLoader(ClassLoader parent, Application app) {
            super(parent, app);
        }
        
        @Override
        protected void enhanceClass(ClassDescription desc) {
            enhance(desc);
        }
    }
}
