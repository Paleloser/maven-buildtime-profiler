package com.soebes.maven.extensions.artifact;

import java.text.NumberFormat;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.soebes.maven.extensions.TimePlusSize;

public class DeployTimer extends AbstractArtifactTimer
{
    private final Logger LOGGER = LoggerFactory.getLogger( getClass() );

    public DeployTimer()
    {
        super();
    }

    public void report()
    {
        LOGGER.info( "Deployment summary:" );
        long totalInstallationTime = 0;
        long totalInstallationSize = 0;
        for ( Entry<String, TimePlusSize> item : this.getTimerEvents().entrySet() )
        {
            totalInstallationTime += item.getValue().getElapsedTime();
            totalInstallationSize += item.getValue().getSize();
            LOGGER.info( "{} ms : {}", String.format( "%8d", item.getValue().getElapsedTime() ), item.getKey() );
        }
        LOGGER.info( "{} ms  {} bytes.", NumberFormat.getIntegerInstance().format( totalInstallationTime ),
                     NumberFormat.getIntegerInstance().format( totalInstallationSize ) );
        LOGGER.info( "------------------------------------------------------------------------" );
    }

}
