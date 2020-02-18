package com.soebes.maven.extensions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.SignedObject;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionEvent.Type;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.project.DependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryEvent.EventType;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.soebes.maven.extensions.artifact.DeployTimer;
import com.soebes.maven.extensions.artifact.DownloadTimer;
import com.soebes.maven.extensions.artifact.InstallTimer;
import com.soebes.maven.extensions.metadata.MetadataDeploymentTimer;
import com.soebes.maven.extensions.metadata.MetadataDownloadTimer;
import com.soebes.maven.extensions.metadata.MetadataInstallTimer;
import com.soebes.maven.extensions.reporter.ElasticsearchReporter;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

/**
 * @author Karl Heinz Marbaise <a href="mailto:kama@soebes.de">kama@soebes.de</a>
 */
@Named
@Singleton
public class BuildTimeProfiler
    extends LifeCycleOrdering
{
    private final Logger LOGGER = LoggerFactory.getLogger( getClass() );

    private final String elasticsearchAddress = "localhost";

    private final int elasticsearchPort = 9200;

    private final String elasticsearchIndex = "maven-buildtime-profiler";

    final List<String> lifeCyclePhases;

    private final DiscoveryTimer discoveryTimer;

    private final GoalTimer goalTimer;

    private final MojoTimer mojoTimer;

    private final ProjectTimer projectTimer;

    private final SessionTimer sessionTimer;

    private final InstallTimer installTimer;

    private final DeployTimer deployTimer;

    private final DownloadTimer downloadTimer;

    private final MetadataDownloadTimer metadataDownloadTimer;

    private final MetadataDeploymentTimer metadataDeploymentTimer;

    private final MetadataInstallTimer metadataInstallTimer;

    private final ForkTimer forkTimer;

    private final ProjectTimer forkProject;

    private final ElasticsearchReporter elasticsearchReporter;

    private final String[] ignoreReportFields = new String[]{
        "download",
        "metadata",
        "build.plugins",
        "build.projects",
        "install",
        "fork-project",
        "fork-time",
        "goals"
    };

    public BuildTimeProfiler()
    {
        LOGGER.debug( "LifeCycleProfiler ctor called." );
        this.lifeCyclePhases = Collections.<String>synchronizedList( new LinkedList<String>() );
        this.discoveryTimer = new DiscoveryTimer();
        this.goalTimer = new GoalTimer();
        this.mojoTimer = new MojoTimer();
        this.projectTimer = new ProjectTimer();
        this.sessionTimer = new SessionTimer();
        this.installTimer = new InstallTimer();
        this.deployTimer = new DeployTimer();
        this.downloadTimer = new DownloadTimer();

        this.metadataDownloadTimer = new MetadataDownloadTimer();
        this.metadataDeploymentTimer = new MetadataDeploymentTimer();
        this.metadataInstallTimer = new MetadataInstallTimer();
        this.forkTimer = new ForkTimer();
        this.forkProject = new ProjectTimer();

        this.elasticsearchReporter = new ElasticsearchReporter(elasticsearchAddress, elasticsearchPort, elasticsearchIndex);
    }

    @Override
    public void init( Context context )
        throws Exception
    {
        super.init( context );
        LOGGER.info( "Maven Build Time Profiler started. (Version {})", BuildTimeProfilerVersion.getVersion() );

        // Is this always in the context? Based on Maven Core yes.
        String workingDirectory = (String) context.getData().get( "workingDirectory" );
        LOGGER.debug( "MBTP: workingDirectory: " + workingDirectory );

        String multiModuleProjectDirectory = (String) context.getData().get( "multiModuleProjectDirectory" );
        LOGGER.debug( "MBTP: multiModuleProjectDirectory: " + multiModuleProjectDirectory );

        // Properties systemProperties = (Properties) context.getData().get( "systemProperties" );
        // for ( String propName : systemProperties.stringPropertyNames() )
        // {
        // String propValue = systemProperties.getProperty( propName );
        // LOGGER.info( " systemProperty " + propName + ": '" + propValue + "'" );
        // }
        //
        // Properties userProperties = (Properties) context.getData().get( "userProperties" );
        // for ( String propName : userProperties.stringPropertyNames() )
        // {
        // String propValue = userProperties.getProperty( propName );
        // LOGGER.info( " userProperty " + propName + ": '" + propValue + "'" );
        // }
        // data.put( "plexus", container );
        // data.put( "workingDirectory", cliRequest.workingDirectory );
        // data.put( "systemProperties", cliRequest.systemProperties );
        // data.put( "userProperties", cliRequest.userProperties );
        // data.put( "versionProperties", CLIReportingUtils.getBuildProperties() );

    }

    @Override
    public void onEvent( Object event )
        throws Exception
    {
        try
        {
            if ( event instanceof ExecutionEvent )
            {
                executionEventHandler( (ExecutionEvent) event );
            }
            else if ( event instanceof org.eclipse.aether.RepositoryEvent )
            {
                repositoryEventHandler( (RepositoryEvent) event );
            }
            else if ( event instanceof MavenExecutionRequest )
            {
                executionRequestEventHandler( (MavenExecutionRequest) event );
            }
            else if ( event instanceof MavenExecutionResult )
            {
                executionResultEventHandler( (MavenExecutionResult) event );
            }
            else if ( event instanceof DependencyResolutionRequest )
            {
                dependencyResolutionRequest( (DependencyResolutionRequest) event );
            }
            else if ( event instanceof DependencyResolutionResult )
            {
                dependencyResolutionResult( (DependencyResolutionResult) event );
            }
            // The following event type is available since Maven 3.3.1+
            // else if ( event instanceof DefaultSettingsBuildingRequest) {
            // DefaultSettingsBuildingRequest r = null;
            // r.getGlobalSettingsFile();
            // r.getGlobalSettingsSource();
            // r.getSystemProperties();
            // r.getUserSettingsFile();
            // r.getUserSettingsSource();
            //
            // r.setGlobalSettingsFile( globalSettingsFile );
            // r.setGlobalSettingsSource( globalSettingsSource );
            // r.setSystemProperties( systemProperties );
            // r.setUserProperties( userProperties );
            // r.setUserSettingsFile( userSettingsFile );
            // r.setUserSettingsSource( userSettingsSource );
            // }
            // The following event type is available since Maven 3.3.1+
            // else if (event instanceof DefaultSettingsBuildingRequest) {
            //
            // DefaultSettingsBuildingRequest r = null;
            // r.getGlobalSettingsSource().getLocation()
            // }
            // The following event type is available since Maven 3.3.1+
            // else if (event instanceof DefaultToolchainsBuildingRequest) {
            // DefaultToolchainsBuildingRequest r = null;
            // r.getGlobalToolchainsSource().
            // }
            // The following event type is available since Maven 3.3.1+
            // else if (event instanceof DefaultToolchainsBuildingResult) {
            // DefaultToolchainsBuildingResult r = null;
            // r.getEffectiveToolchains();
            // r.getProblems();
            // }
            else
            {
                // TODO: What kind of event we haven't considered?
                LOGGER.debug( "MBTP: Event {}", event.getClass().getCanonicalName() );
            }
        }
        catch ( Exception e )
        {
            LOGGER.error( "MBTP: Exception", e );
        }
    }

    @Override
    public void close()
    {
        LOGGER.debug( "MBTP: done." );
    }

    private void dependencyResolutionResult( DependencyResolutionResult event )
    {
        LOGGER.debug( "MBTP: dependencyResolutionResult() {}", event.getResolvedDependencies().size() );
    }

    private void dependencyResolutionRequest( DependencyResolutionRequest event )
    {
        LOGGER.debug( "MBTP: dependencyResolutionRequest()" );
    }

    private void repositoryEventHandler( org.eclipse.aether.RepositoryEvent repositoryEvent )
    {
        EventType type = repositoryEvent.getType();
        switch ( type )
        {
            case ARTIFACT_DOWNLOADING:
                LOGGER.debug( "MBTP: repositoryEventHandler {}", type );
                downloadTimer.start( repositoryEvent );
                break;
            case ARTIFACT_DOWNLOADED:
                LOGGER.debug( "MBTP: repositoryEventHandler {}", type );
                downloadTimer.stop( repositoryEvent );
                break;

            case ARTIFACT_DEPLOYING:
                deployTimer.start( repositoryEvent );
                break;
            case ARTIFACT_DEPLOYED:
                deployTimer.stop( repositoryEvent );
                break;

            case ARTIFACT_INSTALLING:
                installTimer.start( repositoryEvent );
                break;
            case ARTIFACT_INSTALLED:
                installTimer.stop( repositoryEvent );
                break;

            case METADATA_DEPLOYING:
                metadataDeploymentTimer.start( repositoryEvent );
                break;
            case METADATA_DEPLOYED:
                metadataDeploymentTimer.stop( repositoryEvent );
                break;

            case METADATA_DOWNLOADING:
                LOGGER.debug( "MBTP: repositoryEventHandler {}", type );
                metadataDownloadTimer.start( repositoryEvent );
                break;
            case METADATA_DOWNLOADED:
                LOGGER.debug( "MBTP: repositoryEventHandler {}", type );
                metadataDownloadTimer.stop( repositoryEvent );
                break;

            case METADATA_INSTALLING:
                LOGGER.debug( "MBTP: repositoryEventHandler {}", type );
                metadataInstallTimer.start( repositoryEvent );
                break;
            case METADATA_INSTALLED:
                LOGGER.debug( "MBTP: repositoryEventHandler {}", type );
                metadataInstallTimer.stop( repositoryEvent );
                break;

            case ARTIFACT_RESOLVING:
            case ARTIFACT_RESOLVED:
            case ARTIFACT_DESCRIPTOR_INVALID:
            case ARTIFACT_DESCRIPTOR_MISSING:
            case METADATA_RESOLVED:
            case METADATA_RESOLVING:
            case METADATA_INVALID:
                // Those events are not recorded.
                break;

            default:
                LOGGER.error( "MBTP: repositoryEventHandler {}", type );
                break;
        }
    }

    private void executionEventHandler( ExecutionEvent executionEvent )
    {
        LOGGER.debug( "executionEventHandler: {}", executionEvent.getType() );
        Type type = executionEvent.getType();
        switch ( type )
        {
            case ProjectDiscoveryStarted:
                // Start reading the pom files..
                discoveryTimer.discoveryStart();
                break;
            case SessionStarted:
                // Reading of pom files done and structure now there.
                discoveryTimer.discoveryStop();
                sessionTimer.sessionStart();
                break;
            case SessionEnded:
                // Everything is done.
                sessionTimer.sessionStop();
                break;

            case ForkStarted:
                forkTimer.start();
                break;
            case ForkFailed:
            case ForkSucceeded:
                forkTimer.stop();
                break;

            case ForkedProjectStarted:
                forkProject.projectStart( executionEvent );
                break;
            case ForkedProjectFailed:
            case ForkedProjectSucceeded:
                forkProject.projectStop( executionEvent );
                break;

            case MojoStarted:
                String phaseStart = executionEvent.getMojoExecution().getLifecyclePhase();
                // Key: phase, project, mojo
                if ( phaseStart == null )
                {
                    goalTimer.mojoStart( executionEvent );
                }
                else
                {
                    collectAllLifeCylcePhases( phaseStart );
                    mojoTimer.mojoStart( executionEvent );
                }
                break;

            case MojoFailed:
            case MojoSucceeded:
            case MojoSkipped:
                String phaseStop = executionEvent.getMojoExecution().getLifecyclePhase();
                if ( phaseStop == null )
                {
                    goalTimer.mojoStop( executionEvent );
                }
                else
                {
                    mojoTimer.mojoStop( executionEvent );
                }
                break;

            case ProjectStarted:
                projectTimer.projectStart( executionEvent );
                break;

            case ProjectFailed:
            case ProjectSucceeded:
            case ProjectSkipped:
                projectTimer.projectStop( executionEvent );
                break;

            default:
                LOGGER.error( "MBTP: executionEventHandler: {}", type );
                break;
        }

    }

    private void executionRequestEventHandler( MavenExecutionRequest event )
    {
        // event.getUserProperties().put( "revision", "1.2.3-SNAPSHOT" );
        // event.getSystemProperties().put( "revision", "1.2.3-SNAPSHOT" );
        // Can we do something more useful here?
        LOGGER.debug( "MBTP: executionRequestEventHandler: {}", event.getExecutionListener() );
    }

    private void executionResultEventHandler( MavenExecutionResult event )
    {
        String output = event.getProject().getProperties().getProperty("maven-buildtime-profiler-output");
        String filename = "";
        String body = "";

        JSONObject document = toJSON();

        if (output != null && !output.equalsIgnoreCase("stdout"))
        {
            switch (output.toLowerCase())
            {
                case "json":
                    body = document.toString();
                    filename = "report.json";
                    break;
                default:
                    return;
            }

            File dest = event.getProject().getProperties().containsKey("maven-buildtime-profiler-directory") ?
                new File(event.getProject().getProperties().getProperty("maven-buildtime-profiler-directory"), filename) :
                new File("target/", filename);

            try (FileWriter file = new FileWriter(dest))
            {
                file.write(body);
            }
            catch (IOException e)
            {
                LOGGER.error("Couldn't save document: {}", e.getMessage());
                return;
            }
        }
        else
        {
            report(event);
        }

        sendTelemetry(event, document);
    }

    private void report(MavenExecutionResult event)
    {
        orderLifeCycleOnPreparedOrder( lifeCyclePhases );

        LOGGER.debug( "MBTP: executionResultEventHandler: {}", event.getProject() );

        LOGGER.info( "--             Maven Build Time Profiler Summary                      --" );
        LOGGER.info( "------------------------------------------------------------------------" );

        discoveryTimer.report();

        if ( mojoTimer.hasEvents() )
        {
            LOGGER.info( "Project Build Time (reactor order):" );
            LOGGER.info( "" );
            for ( MavenProject mavenProject : event.getTopologicallySortedProjects() )
            {
                LOGGER.info( "{}:", mavenProject.getName() );

                for ( String phase : lifeCyclePhases )
                {
                    ProjectKey projectKey = mavenProjectToProjectKey( mavenProject );

                    if ( !mojoTimer.hasTimeForProjectAndPhase( projectKey, phase ) )
                    {
                        continue;
                    }

                    long timeForPhaseAndProjectInMillis =
                        mojoTimer.getTimeForProjectAndPhaseInMillis( projectKey, phase );
                    LOGGER.info( "    {} ms : {}", String.format( "%8d", timeForPhaseAndProjectInMillis ), phase );

                }

            }
            LOGGER.info( "------------------------------------------------------------------------" );
            LOGGER.info( "Lifecycle Phase summary:" );
            LOGGER.info( "" );
            for ( String phase : lifeCyclePhases )
            {
                long timeForPhaseInMillis = mojoTimer.getTimeForPhaseInMillis( phase );
                LOGGER.info( "{} ms : {}", String.format( "%8d", timeForPhaseInMillis ), phase );
            }

            // List all plugins per phase
            LOGGER.info( "------------------------------------------------------------------------" );
            LOGGER.info( "Plugins in lifecycle Phases:" );
            LOGGER.info( "" );
            for ( String phase : lifeCyclePhases )
            {
                LOGGER.info( "{}:", phase );
                Map<ProjectMojo, SystemTime> plugisInPhase = mojoTimer.getPluginsInPhase( phase );
                for ( Entry<ProjectMojo, SystemTime> pluginInPhase : plugisInPhase.entrySet() )
                {
                    LOGGER.info( "{} ms: {}", String.format( "%8d", pluginInPhase.getValue().getElapsedTime() ),
                        pluginInPhase.getKey().getMojo().getFullId() );
                }

            }
            LOGGER.info( "------------------------------------------------------------------------" );
        }

        if ( goalTimer.hasEvents() )
        {
            LOGGER.info( "Plugins directly called via goals:" );
            LOGGER.info( "" );
            goalTimer.report();
            LOGGER.info( "------------------------------------------------------------------------" );
        }

        installTimer.report();
        downloadTimer.report();
        deployTimer.report();
        metadataInstallTimer.report();
        metadataDownloadTimer.report();
        metadataDeploymentTimer.report();

        forkTimer.report();
        forkProject.report();
    }

    private JSONObject toJSON()
    {
        JSONObject jsonObject = new JSONObject();

        jsonObject.put("discovery-time", discoveryTimer.getTime());
        jsonObject.put("build", mojoTimer.toJSON());
        jsonObject.put("goals", goalTimer.toJSON());
        jsonObject.put("install", installTimer.toJSON());
        jsonObject.put("download", downloadTimer.toJSON());
        jsonObject.put("deploy", deployTimer.toJSON());
        JSONObject metadata = new JSONObject();
        metadata.put("install", metadataInstallTimer.toJSON());
        metadata.put("download", metadataDownloadTimer.toJSON());
        metadata.put("deployment", metadataDeploymentTimer.toJSON());
        jsonObject.put("metadata", metadata);
        jsonObject.put("fork-time", forkTimer.getTime());
        jsonObject.put("fork-project", forkProject.toJSON());

        return jsonObject;
    }

    private void sendTelemetry(MavenExecutionResult event, JSONObject profiling)
    {
        removeJSONFields(profiling, ignoreReportFields);

        JSONObject document = new JSONObject();
        document.put("profiling", profiling);

        JSONObject project = new JSONObject();
        project.put("id", event.getProject().getId());
        project.put("groupId", event.getProject().getGroupId());
        project.put("artifactId", event.getProject().getArtifactId());
        project.put("version", event.getProject().getVersion());

        JSONObject parent = new JSONObject();
        parent.put("groupId", event.getProject().getParent().getGroupId());
        parent.put("artifactId", event.getProject().getParent().getArtifactId());
        parent.put("version", event.getProject().getParent().getVersion());

        project.put("parent", parent);
        document.put("project", project);

        document.put("system", getSystemTelemetry());

        elasticsearchReporter.index(document);
    }

    private JSONObject getSystemTelemetry()
    {
        SystemInfo sysInfo = new SystemInfo();

        HardwareAbstractionLayer hardware = sysInfo.getHardware();
        OperatingSystem osInfo = sysInfo.getOperatingSystem();

        JSONObject system = new JSONObject();

        JSONObject os = new JSONObject();
        os.put("arch", System.getProperty("os.arch"));
        os.put("name", System.getProperty("os.name"));
        os.put("version", System.getProperty("os.version"));
        os.put("build", osInfo.getVersionInfo().getBuildNumber());

        JSONObject java = new JSONObject();

        JSONObject javaVm = new JSONObject();
        javaVm.put("specification", System.getProperty("java.vm.specification.version"));
        javaVm.put("version", System.getProperty("java.vm.version"));
        javaVm.put("vendor", System.getProperty("java.vm.vendor"));

        JSONObject javaVmMemory = new JSONObject();
        javaVmMemory.put("total", Runtime.getRuntime().totalMemory());
        javaVmMemory.put("available", Runtime.getRuntime().freeMemory());
        javaVmMemory.put("max", Runtime.getRuntime().maxMemory());

        javaVm.put("memory", javaVmMemory);

        java.put("vm", javaVm);
        java.put("runtime", System.getProperty("java.runtime.version"));

        JSONObject processor = new JSONObject();
        processor.put("id", hardware.getProcessor().getProcessorIdentifier().getIdentifier());
        processor.put("name", hardware.getProcessor().getProcessorIdentifier().getName());
        processor.put("logicalProcessors", hardware.getProcessor().getLogicalProcessorCount());
        processor.put("physicalProcessors", hardware.getProcessor().getPhysicalProcessorCount());
        processor.put("frequency", hardware.getProcessor().getMaxFreq());

        JSONObject memory = new JSONObject();
        memory.put("total", hardware.getMemory().getTotal());
        memory.put("available", hardware.getMemory().getAvailable());

        system.put("memory", memory);
        system.put("processor", processor);
        system.put("os", os);
        system.put("java", java);

        return system;
    }

    private void removeJSONFields(JSONObject document, String[] fields) {
        for (String field : fields)
        {
            removeFieldToDocument(document, field);
        }
    }

    private JSONObject removeFieldToDocument(JSONObject document, String field)
    {
        if (field.contains("."))
        {
            String key = field.substring(0, field.indexOf('.'));
            String subkey = field.substring(field.indexOf('.') + 1);

            if (document.has(key))
            {
                document.put(key, removeFieldToDocument((JSONObject) document.get(key), subkey));
            }
        }
        else if (document.has(field))
        {
            document.remove(field);
        }
        return document;
    }

    private ProjectKey mavenProjectToProjectKey( MavenProject project )
    {
        return new ProjectKey( project.getGroupId(), project.getArtifactId(), project.getVersion() );
    }

    private void collectAllLifeCylcePhases( String phase )
    {
        // Phase can be null if you call maven via:
        // mvn site:stage (no life cycle started.)
        if ( phase == null )
        {
            return;
        }
        LOGGER.debug( "collectAllLifeCyclePhases({})", phase );
        if ( !lifeCyclePhases.contains( phase ) )
        {
            lifeCyclePhases.add( phase );
        }
    }

}
