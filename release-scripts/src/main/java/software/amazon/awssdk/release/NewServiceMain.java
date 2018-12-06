/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.release;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

/**
 * A command line application to create a new, empty service.
 *
 * Example usage:
 * <pre>
 * mvn exec:java -pl :release-scripts \
 *     -Dexec.mainClass="software.amazon.awssdk.release.NewServiceMain" \
 *     -Dexec.args="--maven-project-root /path/to/root
 *                  --maven-project-version 2.1.4-SNAPSHOT
 *                  --service-id 'Service Id'
 *                  --service-module-name service-module-name
 *                  --service-protocol json"
 * </pre>
 */
public class NewServiceMain {
    private static final Logger log = Logger.loggerFor(NewServiceMain.class);

    private NewServiceMain() {}

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption(requiredOption("service-module-name", "The name of the service module to be created."));
        options.addOption(requiredOption("service-id", "The service ID of the service module to be created."));
        options.addOption(requiredOption("service-protocol", "The protocol of the service module to be created."));
        options.addOption(requiredOption("maven-project-root", "The root directory for the maven project."));
        options.addOption(requiredOption("maven-project-version", "The maven version of the service module to be created."));

        CommandLineParser parser = new DefaultParser();
        HelpFormatter help = new HelpFormatter();

        try {
            CommandLine commandLine = parser.parse(options, args);
            new NewServiceCreator(commandLine).run();
        } catch (ParseException e) {
            log.error(() -> "Invalid input: " + e.getMessage());
            help.printHelp("NewServiceMain", options);
            System.exit(1);
        } catch (Exception e) {
            log.error(() -> "Script execution failed.", e);
            System.exit(2);
        }
    }

    private static Option requiredOption(String longCommand, String description) {
        Option option = new Option(null, longCommand, true, description);
        option.setRequired(true);
        return option;
    }

    private static class NewServiceCreator {
        private final Path mavenProjectRoot;
        private final String mavenProjectVersion;
        private final String serviceModuleName;
        private final String serviceId;
        private final String serviceProtocol;

        private NewServiceCreator(CommandLine commandLine) {
            this.mavenProjectRoot = Paths.get(commandLine.getOptionValue("maven-project-root"));
            this.mavenProjectVersion = commandLine.getOptionValue("maven-project-version");
            this.serviceModuleName = commandLine.getOptionValue("service-module-name");
            this.serviceId = commandLine.getOptionValue("service-id");
            this.serviceProtocol = transformSpecialProtocols(commandLine.getOptionValue("service-protocol"));
        }

        private String transformSpecialProtocols(String protocol) {
            switch (protocol) {
                case "ec2": return "query";
                case "rest-xml": return "xml";
                case "rest-json": return "json";
                default: return protocol;
            }
        }

        public void run() throws Exception {
            Path servicesRoot = mavenProjectRoot.resolve("services");
            Path templateModulePath = servicesRoot.resolve("new-service-template");
            Path newServiceModulePath = servicesRoot.resolve(serviceModuleName);

            createNewModuleFromTemplate(templateModulePath, newServiceModulePath);
            replaceTemplatePlaceholders(newServiceModulePath);

            Path servicesPomPath = mavenProjectRoot.resolve("services").resolve("pom.xml");
            Path aggregatePomPath = mavenProjectRoot.resolve("aws-sdk-java").resolve("pom.xml");
            Path bomPomPath = mavenProjectRoot.resolve("bom").resolve("pom.xml");

            new AddSubmoduleTransformer().transform(servicesPomPath);
            new AddDependencyTransformer().transform(aggregatePomPath);
            new AddDependencyManagementDependencyTransformer().transform(bomPomPath);
        }

        private void createNewModuleFromTemplate(Path templateModulePath, Path newServiceModule) throws IOException {
            FileUtils.copyDirectory(templateModulePath.toFile(), newServiceModule.toFile());
        }

        private void replaceTemplatePlaceholders(Path newServiceModule) throws IOException {
            Files.walkFileTree(newServiceModule, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    replacePlaceholdersInFile(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private void replacePlaceholdersInFile(Path file) throws IOException {
            String fileContents = new String(Files.readAllBytes(file), UTF_8);
            String newFileContents = replacePlaceholders(fileContents);
            Files.write(file, newFileContents.getBytes(UTF_8));
        }

        private String replacePlaceholders(String line) {
            String[] searchList = {
                    "{{MVN_ARTIFACT_ID}}",
                    "{{MVN_NAME}}",
                    "{{MVN_VERSION}}",
                    "{{PROTOCOL}}"
            };
            String[] replaceList = {
                    serviceModuleName,
                    serviceId,
                    mavenProjectVersion,
                    serviceProtocol
            };
            return StringUtils.replaceEach(line, searchList, replaceList);
        }

        private class AddSubmoduleTransformer extends PomTransformer {
            @Override
            protected void updateDocument(Document doc) {
                Node project = findChild(doc, "project");
                Node modules = findChild(project, "modules");

                modules.appendChild(textElement(doc, "module", serviceModuleName));
            }
        }

        private class AddDependencyTransformer extends PomTransformer {
            @Override
            protected void updateDocument(Document doc) {
                Node project = findChild(doc, "project");
                Node dependencies = findChild(project, "dependencies");

                dependencies.appendChild(sdkDependencyElement(doc, serviceModuleName));
            }
        }

        private class AddDependencyManagementDependencyTransformer extends PomTransformer {
            @Override
            protected void updateDocument(Document doc) {
                Node project = findChild(doc, "project");
                Node dependencyManagement = findChild(project, "dependencyManagement");
                Node dependencies = findChild(dependencyManagement, "dependencies");

                dependencies.appendChild(sdkDependencyElement(doc, serviceModuleName));
            }
        }
    }
}
