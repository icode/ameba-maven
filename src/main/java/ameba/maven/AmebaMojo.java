package ameba.maven;

import ameba.core.Application;
import ameba.dev.Enhancing;
import ameba.dev.classloading.ClassDescription;
import ameba.dev.classloading.ReloadClassLoader;
import ameba.dev.classloading.enhancers.Enhanced;
import ameba.dev.classloading.enhancers.Enhancer;
import ameba.dev.classloading.enhancers.EnhancingException;
import ameba.dev.info.MavenProjects;
import ameba.dev.info.ProjectInfo;
import ameba.util.ClassUtils;
import com.google.common.collect.Sets;
import javassist.ClassPool;
import javassist.CtClass;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.slf4j.impl.StaticLoggerBinder;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
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
    private static final String LINE_SEPARATOR = System.getProperty("line.separator", "\n");
    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     */
    private MavenProject project;
    /**
     * Set the directory holding the class files we want to transform.
     *
     * @parameter default-value="${project.build.outputDirectory}"
     */
    private String classSource;
    /**
     * Set the directory holding the class files we want to transform.
     *
     * @parameter default-value="${project.build.directory}/generated-sources/ameba"
     */
    private File confDir;
    /**
     * Set the application config ids.
     *
     * @parameter
     */
    private String[] ids;
    private ReloadClassLoader classLoader;

    @SuppressWarnings("unchecked")
    public void execute()
            throws MojoExecutionException {
        final Log log = getLog();
        StaticLoggerBinder.getSingleton().setMavenLog(log);
        if (classSource == null) {
            classSource = project.getBuild().getOutputDirectory();
        }

        File f = project.getBasedir();
        log.info("Current Directory: " + f.getAbsolutePath());
        ClassLoader oldClassLoader = ClassUtils.getContextClassLoader();
        File sourceDir = new File(project.getBuild().getSourceDirectory()).getAbsoluteFile();
        log.debug("Java source Directory: " + sourceDir);
        classLoader = buildClassLoader();
        log.debug("ReloadClassLoader ClassPath: [" + StringUtils.join(classLoader.getURLs(), LINE_SEPARATOR) + "]");

        Thread.currentThread().setContextClassLoader(classLoader);

        Properties properties = Application.readDefaultConfig();
        Application.readAppConfig(properties, Application.DEFAULT_APP_CONF);
        if (ids != null && ids.length > 0) {
            Set<String> configFiles = Application.parseIds2ConfigFile(ids);
            for (String conf : configFiles) {
                Application.readAppConfig(properties, conf);
            }
        }

        properties.stringPropertyNames().forEach(key -> {
            if (key.startsWith("env.")) {
                System.setProperty(key.substring(4), properties.getProperty(key));
            }
        });

        Application.readModuleConfig(properties, false);

        String encoding = (String) project.getProperties().get("project.build.sourceEncoding");
        if (StringUtils.isBlank(encoding)) encoding = "utf-8";
        properties.setProperty("app.encoding", encoding);
        properties.setProperty("ebean.enhancer.log.level", "0");

        Enhancing.loadEnhancers((Map) properties);

        log.info("Enhancing classes ...");
        process("", true);
        Thread.currentThread().setContextClassLoader(oldClassLoader);
        log.info("Enhanced classes done");
    }

    private void process(String dir, boolean recurse) {

        String dirPath = classSource + "/" + dir;
        File d = new File(dirPath);
        if (!d.exists()) {
            File currentDir = new File(".");
            String m = "File not found " + dirPath + "  currentDir:" + currentDir.getAbsolutePath();
            throw new RuntimeException(m);
        }

        getLog().debug("List file in [" + d.getAbsolutePath() + "]");
        File[] files = d.listFiles();
        File f = null;
        try {
            if (files != null) {
                for (File file : files) {
                    f = file;
                    if (file.isDirectory()) {
                        if (recurse) {
                            String subdir = dir + "/" + file.getName();
                            process(subdir, true);
                        }
                    } else {
                        String fileName = file.getName();
                        if (fileName.endsWith(".java")) {
                            // possibly a common mistake... mixing .java and .class
                            getLog().debug("Expecting a .class file but got " + fileName + " ... ignoring");

                        } else if (fileName.endsWith(".class")) {
                            String name = file.getPath().substring(classSource.length());
                            transformFile(name);
                        }
                    }
                }
            } else {
                getLog().debug("cannot find file in [" + d.getAbsolutePath() + "]");
            }
        } catch (Exception e) {
            if (f != null && f.isFile()) {
                String m = "Error transforming file " + f.getPath();
                throw new RuntimeException(m, e);
            } else {
                throw e;
            }
        }
    }

    private void transformFile(String file) {
        getLog().debug("transformFile [" + file + "]");
        if (file.startsWith(File.separator)) {
            file = file.substring(1);
        }
        String name = file.replace(File.separator, ".");
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
        if (!(desc.getEnhancedClassFile().exists()
                || clazz.hasAnnotation(Enhanced.class)
                || clazz.isInterface()
                || clazz.getName().endsWith(".package")
                || clazz.getName().startsWith("jdk.")
                || clazz.getName().startsWith("java.")
                || clazz.getName().startsWith("javax.")
                || clazz.isEnum()
                || clazz.isPrimitive()
                || clazz.isAnnotation()
                || clazz.isArray())) {

            for (Enhancer enhancer : Enhancing.getEnhancers()) {
                enhance(enhancer, desc);
            }
        }

        if (desc.enhancedByteCode != null) {
            try {
                getLog().debug("Write class file [" + desc.classFile.getAbsolutePath() + "]");
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

    private ReloadClassLoader buildClassLoader() {
        URL[] urls = buildClassPath();
        ClassLoader loader = URLClassLoader.newInstance(urls, Thread.currentThread().getContextClassLoader());
        MavenProjects.load();
        return new MojoClassLoader(loader, ProjectInfo.root());
    }

    private URL[] buildClassPath() {
        try {
            Set<URL> urls = Sets.newLinkedHashSet();

            URL projectOut = new File(project.getBuild().getOutputDirectory()).toURI().toURL();
            urls.add(projectOut);
            urls.add(confDir.toURI().toURL());

            Set<Artifact> artifacts = project.getArtifacts();

            for (Artifact a : artifacts) {
                urls.add(a.getFile().toURI().toURL());
            }

            getLog().debug("Root ClassPath: [" + StringUtils.join(urls, LINE_SEPARATOR) + "]");

            return urls.toArray(new URL[urls.size()]);

        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private class MojoClassLoader extends ReloadClassLoader {
        public MojoClassLoader(ClassLoader parent, ProjectInfo projectInfo) {
            super(parent, projectInfo);
        }

        @Override
        protected void enhanceClass(ClassDescription desc) {
            enhance(desc);
        }
    }
}