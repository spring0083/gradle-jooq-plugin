package nu.studer.gradle.jooq;

import nu.studer.gradle.jooq.property.JooqEditionProperty;
import nu.studer.gradle.jooq.property.JooqVersionProperty;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.util.GradleVersion;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static nu.studer.gradle.jooq.util.Strings.capitalize;

/**
 * Plugin that extends the java-base plugin and registers a {@link JooqTask} for each defined jOOQ configuration. Each task generates the jOOQ source code from the configured
 * database. The tasks properly participate in the Gradle up-to-date checks. The tasks are wired as dependencies of the compilation tasks of the JavaBasePlugin plugin.
 */
@SuppressWarnings("unused")
public class JooqPlugin implements Plugin<Project> {

    public void apply(Project project) {
        // abort if old Gradle version is not supported
        if (GradleVersion.current().getBaseVersion().compareTo(GradleVersion.version("6.0")) < 0) {
            throw new IllegalStateException("This version of the jooq plugin is not compatible with Gradle < 6.0");
        }

        // apply Java base plugin, making it possible to also use the jOOQ plugin for Android builds
        project.getPlugins().apply(JavaBasePlugin.class);

        // allow to configure the jOOQ edition/version and compilation on source code generation via extension property
        JooqEditionProperty.applyDefaultEdition(project);
        JooqVersionProperty.applyDefaultVersion(project);

        // use the configured jOOQ version on all jOOQ dependencies
        enforceJooqEditionAndVersion(project);

        // add rocker DSL extension
        NamedDomainObjectContainer<JooqConfig> container = project.container(JooqConfig.class, name -> project.getObjects().newInstance(JooqConfig.class, name));
        project.getExtensions().add("jooq", container);

        // create configuration for the runtime classpath of the jooq code generator (shared by all jooq configuration domain objects)
        final Configuration runtimeConfiguration = createJooqGeneratorRuntimeConfiguration(project);

        // create a rocker task for each rocker configuration domain object
        container.configureEach(config -> {
            String taskName = "generate" + (config.name.equals("main") ? "" : capitalize(config.name)) + "Jooq";
            TaskProvider<JooqTask> jooq = project.getTasks().register(taskName, JooqTask.class, config, runtimeConfiguration);
            jooq.configure(task -> {
                task.setDescription(String.format("Generates the jOOQ sources from the %s jOOQ configuration.", config.name));
                task.setGroup("jOOQ");
            });

            // add the output of the jooq task as a source directory of the source set with the matching name (which adds an implicit task dependency)
            SourceSetContainer sourceSets = project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets();
            sourceSets.configureEach(sourceSet -> {
                if (sourceSet.getName().equals(config.name)) {
                    // todo (etst) use forUseAtConfigurationTime?
                    sourceSet.getJava().srcDir(config.getGenerateSchemaSourceOnCompilation().get() ? jooq : (Callable<Provider<Directory>>) config::getOutputDir);
                    // todo (etst) add jooq runtime dependency
                }
            });
        });
    }

    /**
     * Forces the jOOQ version and edition selected by the user throughout all dependency configurations.
     */
    private static void enforceJooqEditionAndVersion(Project project) {
        Set<String> jooqGroupIds = Arrays.stream(JooqEdition.values()).map(JooqEdition::getGroupId).collect(Collectors.toSet());
        project.getConfigurations().configureEach(configuration ->
            configuration.getResolutionStrategy().eachDependency(details -> {
                ModuleVersionSelector requested = details.getRequested();
                if (jooqGroupIds.contains(requested.getGroup()) && requested.getName().startsWith("jooq")) {
                    String group = JooqEditionProperty.fromProject(project).asGroupId();
                    String version = JooqVersionProperty.fromProject(project).asVersion();
                    details.useTarget(group + ":" + requested.getName() + ":" + version);
                }
            })
        );
    }

    /**
     * Adds the configuration that holds the classpath to use for invoking jOOQ. Users can add their JDBC driver and any generator extensions they might have. Explicitly add JAXB
     * dependencies since they have been removed from JDK 9 and higher. Explicitly add Activation dependency since it has been removed from JDK 11 and higher.
     */
    private static Configuration createJooqGeneratorRuntimeConfiguration(Project project) {
        Configuration jooqRuntime = project.getConfigurations().create("jooqRuntime"); // todo (etst) rename to jooqGenerator
        jooqRuntime.setDescription("The classpath used to invoke jOOQ. Add your JDBC driver and generator extensions here.");
        project.getDependencies().add(jooqRuntime.getName(), "org.jooq:jooq-codegen");
        project.getDependencies().add(jooqRuntime.getName(), "javax.xml.bind:jaxb-api:2.3.1");
        project.getDependencies().add(jooqRuntime.getName(), "com.sun.xml.bind:jaxb-core:2.3.0.1");
        project.getDependencies().add(jooqRuntime.getName(), "com.sun.xml.bind:jaxb-impl:2.3.0.1");
        project.getDependencies().add(jooqRuntime.getName(), "javax.activation:activation:1.1.1");
        return jooqRuntime;
    }

}
